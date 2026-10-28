package ru.eventpass.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.eventpass.app.di.AndroidFakeAuthStorage
import ru.eventpass.app.di.DemoBackendEntryPoint
import ru.eventpass.core.domain.auth.*
import ru.eventpass.data.fake.product.*

@RunWith(AndroidJUnit4::class)
class AuthFoundationContractTest {
    @Test fun sharedDiAndAtomicAndroidSnapshotPreserveConfirmationWithoutSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = EntryPointAccessors.fromApplication(context, DemoBackendEntryPoint::class.java)
        assertSame(graph.environment().auth, graph.auth())
        val directory = File(context.noBackupFilesDir, "auth-contract-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            fun environment() = FakeEventPassEnvironment(
                Clock.fixed(FakeFixtures.NOW, ZoneOffset.UTC), AndroidFakeAuthStorage(directory),
                FakeAuthPolicy(passwordIterations = 1_000),
            )
            val first = environment()
            val created = first.auth.signUp("李", "android-contract@example.com", AuthSecret("AndroidDemo123!")) as AuthResult.Success
            val mail = first.scenarios.authOutbox().last()
            val cold = environment()
            assertEquals(SessionState.Guest, cold.auth.observeSession().first().state)
            assertEquals(AuthResult.Success(created.value.email), cold.auth.confirmEmail(created.value.id, mail.secret))
            assertEquals(SessionState.Guest, cold.auth.currentSession().state)
            assertTrue(cold.auth.signIn(created.value.email, AuthSecret("AndroidDemo123!")) is AuthResult.Success)
            val restarted = environment()
            assertEquals(SessionState.Guest, restarted.auth.observeSession().first().state)
            assertTrue(restarted.scenarios.authOutbox().isEmpty())
            assertEquals(AuthResult.Failed(AuthError.ConsumedChallenge), restarted.auth.confirmEmail(created.value.id, mail.secret))
        } finally {
            // Only this test's randomly named child in the app's private no-backup directory.
            directory.listFiles()?.forEach { check(it.delete()) }
            check(directory.delete())
        }
    }
}
