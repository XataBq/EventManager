package ru.eventpass.app.feature.auth

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.eventpass.core.domain.auth.*
import ru.eventpass.data.fake.product.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun loginSuppressesConcurrentSubmitAndAllowsRetryAfterTypedFailure() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = LoginViewModel(saved)
        vm.email("alex@example.com"); vm.password("DemoPass123!")
        val result = CompletableDeferred<AuthResult<Unit>>()
        var calls = 0
        val signIn: suspend (String, AuthSecret) -> AuthResult<Unit> = { _, _ -> calls++; result.await() }
        vm.submit(signIn); vm.submit(signIn); runCurrent()
        assertTrue(vm.state.value.busy); assertEquals(1, calls)
        result.complete(AuthResult.Failed(AuthError.Network)); runCurrent()
        assertEquals(AuthError.Network, vm.state.value.error); assertFalse(vm.state.value.busy)
        vm.submit { _, _ -> calls++; AuthResult.Success(Unit) }; runCurrent()
        assertEquals(2, calls); assertEquals("", vm.state.value.password)
        assertEquals(setOf("email"), saved.keys())
    }

    @Test fun signupAndResetStepsUseOneBackendAndRequireManualLogin() = runTest(dispatcher) {
        val clock = Clock.systemUTC()
        val environment = FakeEventPassEnvironment(authClock = clock,
            authPolicy = FakeAuthPolicy(passwordIterations = 1000, resendCooldown = Duration.ZERO))
        val saved = SavedStateHandle()
        val signup = AccountFlowViewModel(environment.auth, saved, clock)
        signup.edit(email = "viewmodel@example.com", name = "Мария 李", password = "OriginalPass123", consent = true)
        signup.submit(false)
        // IO is real; advancing virtual time alone does not complete a dispatched repository call.
        signup.state.first { !it.busy }
        assertEquals(AccountStep.Verification, signup.state.value.step)
        assertEquals("", signup.state.value.password)
        val confirmation = environment.scenarios.authOutbox().last()
        assertTrue(environment.auth.confirmEmail(confirmation.challenge.id, confirmation.secret) is AuthResult.Success)
        assertEquals(SessionState.Guest, environment.auth.currentSession().state)
        val reset = AccountFlowViewModel(environment.auth, SavedStateHandle(), clock)
        reset.edit(email = "viewmodel@example.com"); reset.submit(true)
        reset.state.first { !it.busy }
        assertEquals(AccountStep.Code, reset.state.value.step)
        reset.edit(code = "invalid"); reset.submit(true)
        assertEquals(AuthError.InvalidInput, reset.state.value.error)
        reset.edit(code = environment.scenarios.authOutbox().last().secret.value); reset.submit(true)
        reset.state.first { !it.busy }
        assertEquals(AccountStep.Password, reset.state.value.step)
        reset.edit(password = "ReplacementPass123"); reset.submit(true)
        reset.state.first { !it.busy }
        assertEquals(AccountStep.Done, reset.state.value.step)
        assertEquals(SessionState.Guest, environment.auth.currentSession().state)
        assertTrue(environment.auth.signIn("viewmodel@example.com", AuthSecret("OriginalPass123")) is AuthResult.Failed)
        assertTrue(environment.auth.signIn("viewmodel@example.com", AuthSecret("ReplacementPass123")) is AuthResult.Success)
        val restored = AccountFlowViewModel(environment.auth, saved, clock)
        assertEquals(AccountStep.Input, restored.state.value.step)
        assertEquals("", restored.state.value.password)
        assertEquals(setOf("email"), saved.keys())
    }
}
