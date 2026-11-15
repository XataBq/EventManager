package ru.eventpass.app.di

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import ru.eventpass.data.fake.product.FakeAuthStorage

/** Shared fake-server adapter; backend performs IO off main. Keeps the T04 file path for migration. */
class AndroidFakeAuthStorage(private val directory: File) : FakeAuthStorage {
    private val file = AtomicFile(File(directory, "demo-auth-v1.bin"))
    override fun read(): ByteArray? = try { file.openRead().use { it.readBytes() } }
        catch (error: FileNotFoundException) { if (file.baseFile.exists()) throw error else null }
    override fun backupLegacy(snapshot: ByteArray) {
        val backup = AtomicFile(File(directory, "demo-legacy-before-schema6.bin"))
        val stream = backup.startWrite()
        try { stream.write(snapshot); backup.finishWrite(stream) }
        catch (error: java.io.IOException) { backup.failWrite(stream); throw error }
    }
    override fun write(snapshot: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(snapshot); file.finishWrite(stream) }
        catch (error: java.io.IOException) { file.failWrite(stream); throw error }
    }
}
