package ru.eventpass.data.fake.product

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.event.UserId

/** Independent old-layout writer: production encoder must not silently redefine legacy fixture. */
internal object LegacyAuthV1Fixture {
    fun encode(accounts: Map<UserId, Account>, data: AuthData): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeInt(accounts.size)
            accounts.forEach { (id, account) ->
                out.writeUTF(id.value); out.writeUTF(account.email); out.writeBoolean(account.emailVerified); out.writeUTF(data.names[id].orEmpty())
                val proof = data.passwords[id]; out.writeBoolean(proof != null)
                if (proof != null) { out.writeUTF(proof.salt); out.writeUTF(proof.hash); out.writeInt(proof.iterations) }
            }
            out.writeInt(data.challenges.size)
            data.challenges.values.forEach { c ->
                out.writeUTF(c.id); out.writeUTF(c.userId.value); out.writeBoolean(c.reset); out.writeUTF(c.hash)
                out.writeUTF(c.expiresAt.toString()); out.writeUTF(c.resendAt.toString()); out.writeBoolean(c.consumed)
                out.writeUTF(c.grantHash); out.writeBoolean(c.resetUsed)
            }
        }
    }.toByteArray()
}
