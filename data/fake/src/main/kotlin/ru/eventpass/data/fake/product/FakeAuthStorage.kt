package ru.eventpass.data.fake.product

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import ru.eventpass.core.domain.auth.AuthInputPolicy
import ru.eventpass.core.domain.auth.AuthSecret
import ru.eventpass.core.domain.auth.Session
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.UserId

interface FakeAuthStorage {
    fun read(): ByteArray?
    /** Implementations must replace the snapshot atomically, or throw IOException. */
    fun write(snapshot: ByteArray)
}
object VolatileAuthStorage : FakeAuthStorage {
    override fun read(): ByteArray? = null
    override fun write(snapshot: ByteArray) = Unit
}
data class FakeAuthPolicy(
    val input: AuthInputPolicy = AuthInputPolicy(),
    val challengeLifetime: Duration = Duration.ofMinutes(15),
    val resendCooldown: Duration = Duration.ofMinutes(1),
    val sessionLifetime: Duration = Duration.ofMinutes(30),
    val passwordIterations: Int = 120_000,
) {
    init {
        require(!challengeLifetime.isNegative && !challengeLifetime.isZero)
        require(!resendCooldown.isNegative && sessionLifetime > Duration.ZERO)
        require(passwordIterations in 1_000..1_000_000)
    }
}
interface FakeAuthSecrets {
    fun id(): String
    fun token(): String
    fun resetCode(): String
}
class RandomAuthSecrets : FakeAuthSecrets {
    private val random = SecureRandom()
    override fun id() = UUID.randomUUID().toString()
    override fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
    override fun resetCode() = random.nextInt(1_000_000).toString().padStart(6, '0')
}
internal data class PasswordProof(val salt: String, val hash: String, val iterations: Int) {
    override fun toString() = "PasswordProof([redacted])"
}
internal fun passwordProof(secret: AuthSecret, iterations: Int, salt: String? = null): PasswordProof {
    val saltBytes = salt?.let { Base64.getDecoder().decode(it) } ?: ByteArray(16).also(SecureRandom()::nextBytes)
    val spec = PBEKeySpec(secret.value.toCharArray(), saltBytes, iterations, 256)
    return try {
        PasswordProof(Base64.getEncoder().encodeToString(saltBytes),
            Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded), iterations)
    } finally { spec.clearPassword() }
}
internal fun PasswordProof.matches(secret: AuthSecret) = MessageDigest.isEqual(
    Base64.getDecoder().decode(hash), Base64.getDecoder().decode(passwordProof(secret, iterations, salt).hash),
)
internal fun digest(secret: AuthSecret): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(secret.value.toByteArray(Charsets.UTF_8)),
)
internal data class StoredChallenge(
    val id: String, val userId: UserId, val reset: Boolean, val hash: String,
    val expiresAt: Instant, val resendAt: Instant, val consumed: Boolean = false,
    val grantHash: String = "", val resetUsed: Boolean = false,
)
data class FakeAuthMail(val challenge: ru.eventpass.core.domain.auth.EmailChallenge, val reset: Boolean, val secret: AuthSecret) {
    override fun toString() = "FakeAuthMail(id=${challenge.id}, reset=$reset, secret=[redacted])"
}
internal data class AuthData(
    val passwords: Map<UserId, PasswordProof> = emptyMap(),
    val challenges: Map<String, StoredChallenge> = emptyMap(),
    val names: Map<UserId, String> = emptyMap(),
    val session: Session? = null,
    val generation: Long = 0,
    val healthy: Boolean = true,
    val mail: List<FakeAuthMail> = emptyList(),
    val nextError: ru.eventpass.core.domain.auth.AuthError? = null,
)

/** Only fake-server accounts/proofs/challenges persist. No sessions, outbox secrets or guest tickets. */
internal object AuthSnapshotCodec {
    fun encode(accounts: Map<UserId, Account>, data: AuthData): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(accounts.size)
            for ((id, account) in accounts) {
                out.writeUTF(id.value); out.writeUTF(account.email); out.writeBoolean(account.emailVerified)
                out.writeUTF(data.names[id].orEmpty())
                val proof = data.passwords[id]
                out.writeBoolean(proof != null)
                if (proof != null) { out.writeUTF(proof.salt); out.writeUTF(proof.hash); out.writeInt(proof.iterations) }
            }
            out.writeInt(data.challenges.size)
            for (c in data.challenges.values) {
                out.writeUTF(c.id); out.writeUTF(c.userId.value); out.writeBoolean(c.reset); out.writeUTF(c.hash)
                out.writeUTF(c.expiresAt.toString()); out.writeUTF(c.resendAt.toString())
                out.writeBoolean(c.consumed); out.writeUTF(c.grantHash); out.writeBoolean(c.resetUsed)
            }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): Pair<Map<UserId, Account>, AuthData> {
        require(bytes.size <= 1_048_576) { "Oversized auth snapshot" }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1) { "Unsupported auth snapshot" }
            fun count() = input.readInt().also { require(it in 0..10_000) }
            val accounts = mutableMapOf<UserId, Account>()
            val passwords = mutableMapOf<UserId, PasswordProof>()
            val names = mutableMapOf<UserId, String>()
            repeat(count()) {
                val id = UserId(input.readUTF()); val email = input.readUTF(); val verified = input.readBoolean()
                require(id !in accounts && accounts.values.none { it.email == email } && AuthInputPolicy().validEmail(email))
                accounts[id] = Account(id, email, verified); names[id] = input.readUTF()
                if (input.readBoolean()) {
                    val proof = PasswordProof(input.readUTF(), input.readUTF(), input.readInt())
                    require(proof.iterations in 1_000..1_000_000 && Base64.getDecoder().decode(proof.salt).size == 16 && Base64.getDecoder().decode(proof.hash).size == 32)
                    passwords[id] = proof
                }
            }
            val challenges = mutableMapOf<String, StoredChallenge>()
            repeat(count()) {
                val c = StoredChallenge(input.readUTF(), UserId(input.readUTF()), input.readBoolean(), input.readUTF(),
                    Instant.parse(input.readUTF()), Instant.parse(input.readUTF()),
                    input.readBoolean(), input.readUTF(), input.readBoolean())
                require(c.id !in challenges && c.userId in accounts)
                require(Base64.getDecoder().decode(c.hash).size == 32 && (c.grantHash.isEmpty() || Base64.getDecoder().decode(c.grantHash).size == 32))
                challenges[c.id] = c
            }
            require(input.available() == 0)
            return accounts to AuthData(passwords = passwords, names = names, challenges = challenges)
        }
    }
}
