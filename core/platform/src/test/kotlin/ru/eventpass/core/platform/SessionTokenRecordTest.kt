package ru.eventpass.core.platform

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class SessionTokenRecordTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesFreshNonceAndDoesNotContainPlaintext() {
        val key = key()
        val token = "bearer-for-a-synthetic-test"
        val first = SessionTokenRecord.encrypt(token, key)
        val second = SessionTokenRecord.encrypt(token, key)
        assertEquals(token, SessionTokenRecord.decrypt(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(token))
    }

    @Test fun changedCiphertextOrKeyCannotDecrypt() {
        val key = key()
        val record = SessionTokenRecord.encrypt("test-session", key)
        assertThrows(java.security.GeneralSecurityException::class.java) { SessionTokenRecord.decrypt(record, key()) }
        record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
        assertThrows(java.security.GeneralSecurityException::class.java) { SessionTokenRecord.decrypt(record, key) }
    }

    @Test fun unsupportedVersionTruncationAndOversizedRecordsAreRejected() {
        val key = key()
        val record = SessionTokenRecord.encrypt("test-session", key)
        record[4] = 2
        assertThrows(InvalidSessionTokenRecord::class.java) { SessionTokenRecord.decrypt(record, key) }
        assertThrows(InvalidSessionTokenRecord::class.java) { SessionTokenRecord.decrypt(ByteArray(10), key) }
        assertThrows(InvalidSessionTokenRecord::class.java) { SessionTokenRecord.decrypt(ByteArray(SessionTokenRecord.MAX_RECORD_BYTES + 1), key) }
    }

    @Test fun emptyAndOversizedSecretsAreRejectedWithoutPersisting() {
        assertThrows(InvalidSessionTokenRecord::class.java) { SessionTokenRecord.encrypt("", key()) }
        assertThrows(InvalidSessionTokenRecord::class.java) { SessionTokenRecord.encrypt("x".repeat(4097), key()) }
    }
}
