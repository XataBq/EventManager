package ru.eventpass.core.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.auth.AuthSecret
import ru.eventpass.core.domain.auth.SessionTokenStore

/** Bind as a singleton. The file is excluded from backup by its noBackupFilesDir location.
 * No password or mail-link token belongs here; this store holds only the session bearer.
 */
class AndroidSessionTokenStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    storageId: String = "session-token",
) : SessionTokenStore {
    init { require(storageId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
    private val file = File(context.applicationContext.noBackupFilesDir, "$storageId.enc")
    private val atomic = AtomicFile(file)
    private val alias = "${context.applicationContext.packageName}.eventpass.$storageId"
    private val mutex = Mutex()

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
        generateKey()
    }

    override suspend fun read(): AuthSecret? = withContext(dispatcher) {
        mutex.withLock {
            val record = try {
                atomic.openRead().use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        result.write(buffer, 0, count)
                        if (result.size() > SessionTokenRecord.MAX_RECORD_BYTES) break
                    }
                    result.toByteArray()
                }
            } catch (error: FileNotFoundException) {
                if (file.exists() || File(file.path + ".bak").exists()) throw IOException("Session storage unavailable", error)
                return@withLock null
            }
            try {
                val key = existingKey()
                if (key == null) {
                    clearLocked()
                    null
                } else AuthSecret(SessionTokenRecord.decrypt(record, key))
            } catch (error: GeneralSecurityException) {
                // Invalidated/missing key or modified ciphertext must never restore a session.
                clearLocked()
                null
            } catch (error: InvalidSessionTokenRecord) {
                clearLocked()
                null
            } catch (error: ProviderException) {
                throw IOException("Session key unavailable", error)
            }
        }
    }

    override suspend fun write(token: AuthSecret): Unit = withContext(dispatcher) {
        mutex.withLock {
            val encrypted = try {
                SessionTokenRecord.encrypt(token.value, existingKey() ?: createKey())
            } catch (error: GeneralSecurityException) {
                throw IOException("Session encryption failed", error)
            } catch (error: ProviderException) {
                throw IOException("Session key unavailable", error)
            }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(encrypted)
                stream.fd.sync()
                atomic.finishWrite(stream)
                stream = null
                // AtomicFile may report some rename failures only to logcat: prove replacement.
                val saved = atomic.openRead().use { it.readBytes() }
                if (!saved.contentEquals(encrypted)) throw IOException("Session persistence failed")
            } catch (error: IOException) {
                stream?.let(atomic::failWrite)
                throw IOException("Session persistence failed", error)
            }
        }
    }

    override suspend fun clear(): Unit = withContext(dispatcher) { mutex.withLock { clearLocked() } }

    private fun clearLocked() {
        var failure: IOException? = null
        try { keyStore().deleteEntry(alias) }
        catch (error: GeneralSecurityException) { failure = IOException("Session key deletion failed", error) }
        catch (error: ProviderException) { failure = IOException("Session key deletion failed", error) }
        catch (error: IOException) { failure = IOException("Session key deletion failed", error) }
        atomic.delete()
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any(File::exists)) {
            val deletionFailure = IOException("Session file deletion failed")
            if (failure == null) failure = deletionFailure else failure.addSuppressed(deletionFailure)
        }
        failure?.let { throw it }
    }

    override fun toString() = "AndroidSessionTokenStore([redacted])"
}

internal class InvalidSessionTokenRecord : IOException("Invalid session credential record")

/** Versioned bounded AEAD envelope; pure JVM codec permits tampering/format regression tests. */
internal object SessionTokenRecord {
    private const val MAGIC = 0x4550544b
    private const val VERSION = 1
    private const val MAX_TOKEN_BYTES = 4096
    const val MAX_RECORD_BYTES = MAX_TOKEN_BYTES + 64
    private val aad = "EventPass.session-token.v1".toByteArray(Charsets.UTF_8)

    fun encrypt(value: String, key: SecretKey): ByteArray {
        val plaintext = value.toByteArray(Charsets.UTF_8)
        if (plaintext.isEmpty() || plaintext.size > MAX_TOKEN_BYTES) throw InvalidSessionTokenRecord()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return try {
            val encrypted = cipher.doFinal(plaintext)
            ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use {
                    it.writeInt(MAGIC)
                    it.writeByte(VERSION)
                    it.writeByte(cipher.iv.size)
                    it.write(cipher.iv)
                    it.write(encrypted)
                }
            }.toByteArray()
        } finally { plaintext.fill(0) }
    }

    fun decrypt(record: ByteArray, key: SecretKey): String {
        if (record.size !in 34..MAX_RECORD_BYTES) throw InvalidSessionTokenRecord()
        val input = DataInputStream(ByteArrayInputStream(record))
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION || input.readUnsignedByte() != 12)
            throw InvalidSessionTokenRecord()
        val iv = ByteArray(12).also(input::readFully)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(input.readBytes())
        return try {
            if (plaintext.isEmpty() || plaintext.size > MAX_TOKEN_BYTES) throw InvalidSessionTokenRecord()
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plaintext)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) { throw InvalidSessionTokenRecord() }
        } finally { plaintext.fill(0) }
    }
}
