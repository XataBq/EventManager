package ru.eventpass.app.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ru.eventpass.app.R
import ru.eventpass.app.navigation.auth.AppSessionViewModel
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.auth.AuthError
import java.time.Duration

fun authErrorResource(error: AuthError): Int = when (error) {
    AuthError.InvalidInput -> R.string.auth_invalid_input
    AuthError.InvalidCredentials -> R.string.auth_invalid_credentials
    AuthError.EmailTaken -> R.string.auth_email_taken
    AuthError.EmailUnverified -> R.string.auth_unverified
    AuthError.InvalidChallenge -> R.string.auth_invalid_code
    AuthError.ExpiredChallenge -> R.string.auth_expired_code
    AuthError.ConsumedChallenge -> R.string.auth_consumed_code
    AuthError.AlreadyVerified -> R.string.auth_already_verified
    is AuthError.Cooldown -> R.string.auth_cooldown
    AuthError.Network -> R.string.auth_network
    AuthError.Timeout -> R.string.auth_timeout
    AuthError.StorageUnavailable -> R.string.auth_storage
    AuthError.SessionChanged -> R.string.auth_session_changed
    AuthError.Busy -> R.string.auth_busy
}

@Composable
fun AuthErrorText(error: AuthError?) {
    error?.let { Text(stringResource(authErrorResource(it)), color = EventPassColors.danger, modifier = Modifier.testTag("auth-error")) }
}

@Composable
fun AuthColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(EventPassDimens.page),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.large), content = content)
}

@Composable
fun LoginRoute(session: AppSessionViewModel, onSignUp: () -> Unit, onReset: () -> Unit, onVerification: () -> Unit, onDemo: () -> Unit) {
    val vm: LoginViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val prefill by session.loginEmail.collectAsStateWithLifecycle()
    LaunchedEffect(prefill) { if (prefill.isNotEmpty()) vm.email(prefill) }
    LoginScreen(state, session.coordinator.pendingForSavedState() != null, vm::email, vm::password,
        { vm.submit(session::signIn) }, onSignUp, { session.prefill(state.email); onReset() },
        { session.prefill(state.email); onVerification() }, onDemo)
}

@Composable
fun LoginScreen(state: LoginUiState, returning: Boolean, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    onSubmit: () -> Unit, onSignUp: () -> Unit, onReset: () -> Unit, onVerification: () -> Unit, onDemo: () -> Unit) {
    AuthColumn {
        Text(stringResource(R.string.auth_login), style = EventPassTypography.heading, modifier = Modifier.testTag("login-screen"))
        Text(stringResource(if (returning) R.string.auth_return_hint else R.string.auth_login_hint))
        FormField(state.email, onEmail, stringResource(R.string.auth_email), Modifier.testTag("auth-email"),
            enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        PasswordField(state.password, onPassword, stringResource(R.string.auth_password), Modifier.testTag("auth-password"), enabled = !state.busy)
        AuthErrorText(state.error)
        TextAction(stringResource(R.string.auth_forgot), onReset, enabled = !state.busy)
        EventPassButton(stringResource(R.string.auth_login), onSubmit, Modifier.fillMaxWidth().testTag("auth-submit"), loading = state.busy)
        if (state.error == AuthError.EmailUnverified) TextAction(stringResource(R.string.auth_request_verification), onVerification)
        TextAction(stringResource(R.string.auth_signup), onSignUp, enabled = !state.busy)
        Text(stringResource(R.string.auth_event_hint), style = EventPassTypography.hint)
        TextAction(stringResource(R.string.auth_demo_tools), onDemo)
    }
}

@Composable
fun AccountFlowRoute(reset: Boolean, session: AppSessionViewModel, onLogin: () -> Unit, onOutbox: () -> Unit, verification: Boolean = false) {
    val vm: AccountFlowViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(vm.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.resendAt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = vm.now()
            while (state.resendAt?.let { vm.now() < it } == true) { delay(1000); now = vm.now() }
        }
    }
    LaunchedEffect(Unit) {
        if (verification) vm.verification(session.loginEmail.value)
        else if (state.email.isEmpty()) vm.edit(email = session.loginEmail.value)
    }
    AccountFlowScreen(state, reset,
        state.resendAt?.let { ((Duration.between(now, it).toMillis() + 999) / 1000).coerceAtLeast(0) } ?: 0,
        { vm.edit(email = it) }, { vm.edit(name = it) }, { vm.edit(password = it) }, { vm.edit(code = it) },
        { vm.edit(consent = it) }, { vm.submit(reset) }, { vm.resendReset() },
        { session.prefill(state.email); onLogin() }, onOutbox)
}

@Composable
fun AccountFlowScreen(s: AccountUiState, reset: Boolean, cooldown: Long,
    onEmail: (String) -> Unit, onName: (String) -> Unit, onPassword: (String) -> Unit,
    onCode: (String) -> Unit, onConsent: (Boolean) -> Unit, onSubmit: () -> Unit,
    onResend: () -> Unit, onLogin: () -> Unit, onOutbox: () -> Unit) {
    AuthColumn {
        Text(stringResource(if (reset) R.string.auth_reset else R.string.auth_signup), style = EventPassTypography.heading)
        when (s.step) {
            AccountStep.Input -> {
                if (!reset) FormField(s.name, onName, stringResource(R.string.auth_name), Modifier.testTag("auth-name"), enabled = !s.busy)
                FormField(s.email, onEmail, stringResource(R.string.auth_email), Modifier.testTag("auth-email"), enabled = !s.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (!reset) {
                    PasswordField(s.password, onPassword, stringResource(R.string.auth_password), Modifier.testTag("auth-password"), enabled = !s.busy,
                        supportingText = stringResource(R.string.auth_password_hint))
                    ConsentCheckbox(s.consent, onConsent, stringResource(R.string.auth_consent), Modifier.testTag("auth-consent"), enabled = !s.busy)
                }
                EventPassButton(stringResource(if (reset) R.string.auth_send_code else R.string.auth_signup), onSubmit,
                    Modifier.fillMaxWidth().testTag("auth-submit"), loading = s.busy)
            }
            AccountStep.Verification -> {
                Text(stringResource(if (s.sent) R.string.auth_verification_sent else R.string.auth_verification_pending, s.email))
                EventPassButton(stringResource(R.string.auth_resend), onSubmit, enabled = cooldown == 0L, loading = s.busy)
                TextAction(stringResource(R.string.auth_login), onLogin)
            }
            AccountStep.Code -> {
                Text(stringResource(R.string.auth_code_sent, s.email))
                PasswordField(s.code, onCode, stringResource(R.string.auth_code), Modifier.testTag("auth-code"), enabled = !s.busy)
                EventPassButton(stringResource(R.string.auth_verify_code), onSubmit, Modifier.testTag("auth-submit"), loading = s.busy)
                TextAction(stringResource(R.string.auth_resend), onResend, enabled = cooldown == 0L && !s.busy)
            }
            AccountStep.Password -> {
                PasswordField(s.password, onPassword, stringResource(R.string.auth_new_password), Modifier.testTag("auth-password"), enabled = !s.busy)
                EventPassButton(stringResource(R.string.auth_save_password), onSubmit, Modifier.testTag("auth-submit"), loading = s.busy)
            }
            AccountStep.Done -> {
                Text(stringResource(R.string.auth_reset_done))
                EventPassButton(stringResource(R.string.auth_login), onLogin)
            }
        }
        AuthErrorText(s.error)
        if (cooldown > 0 && s.step in listOf(AccountStep.Verification, AccountStep.Code)) Text(stringResource(R.string.auth_retry_seconds, cooldown))
        TextAction(stringResource(R.string.auth_outbox), onOutbox)
    }
}
