package ru.eventpass.core.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.eventpass.core.domain.auth.AuthSecret

@RunWith(AndroidJUnit4::class)
class AndroidSessionTokenStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val storageId = "instrumented-session-token"
    private fun store() = AndroidSessionTokenStore(context, storageId = storageId)

    @After fun cleanup() { runBlocking { store().clear() } }

    @Test fun newInstanceRestoresCredentialAndClearRemovesBothKeyAndFile() = runBlocking {
        store().clear()
        store().write(AuthSecret("synthetic-device-session"))
        assertEquals("synthetic-device-session", store().read()?.value)
        val keyAlias = "${context.packageName}.eventpass.$storageId"
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias))
        store().clear()
        assertNull(store().read())
        assertFalse(File(context.noBackupFilesDir, "$storageId.enc").exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias))
    }

    @Test fun externallyRemovedKeyCannotRestoreSessionAndCanBeReplaced() = runBlocking {
        store().write(AuthSecret("old-synthetic-session"))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("${context.packageName}.eventpass.$storageId")
        assertNull(store().read())
        store().write(AuthSecret("new-synthetic-session"))
        assertEquals("new-synthetic-session", store().read()?.value)
    }

    @Test fun corruptedFileIsClearedInsteadOfRestoringPartialCredentials() = runBlocking {
        store().write(AuthSecret("synthetic-session"))
        File(context.noBackupFilesDir, "$storageId.enc").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store().read())
        assertFalse(File(context.noBackupFilesDir, "$storageId.enc").exists())
    }

    @Test fun failedAtomicReplacementDoesNotReportSuccessfulPersistence(): Unit = runBlocking {
        store().clear()
        val destination = File(context.noBackupFilesDir, "$storageId.enc")
        assertTrue(destination.mkdir())
        val blocker = File(destination, "blocker").apply { writeText("synthetic") }
        try {
            assertThrows(java.io.IOException::class.java) {
                runBlocking { store().write(AuthSecret("must-not-be-accepted")) }
            }
        } finally {
            blocker.delete()
            destination.delete()
            store().clear()
        }
    }
}
