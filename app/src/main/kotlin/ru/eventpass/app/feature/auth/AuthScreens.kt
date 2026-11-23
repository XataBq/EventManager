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
import ru.eventpass.core.domain.auth.AuthInputPolicy
import ru.eventpass.core.domain.auth.AuthSecret
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
    AuthError.Unauthorized -> R.string.auth_invalid_credentials
    AuthError.Forbidden -> R.string.auth_forbidden
    AuthError.Protocol -> R.string.remote_protocol
    AuthError.ServerUnavailable -> R.string.remote_server_unavailable
    AuthError.FeatureUnavailable -> R.string.feature_unavailable
}

@Composable
fun AuthErrorText(error: AuthError?) {
    error?.let { Text(stringResource(authErrorResource(it)), color = EventPassColors.danger, modifier = Modifier.testTag("auth-error")) }
}

@Composable
fun AuthColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(EventPassDimens.page),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.large), content = content)
}

@Composable
fun LoginRoute(session: AppSessionViewModel, onSignUp: () -> Unit, onReset: () -> Unit, onVerification: () -> Unit, onDemo: () -> Unit, showDemo: Boolean = true) {
    val vm: LoginViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val prefill by session.loginEmail.collectAsStateWithLifecycle()
    LaunchedEffect(prefill) { if (prefill.isNotEmpty()) vm.email(prefill) }
    LoginScreen(state, session.coordinator.pendingForSavedState() != null, vm::email, vm::password,
        { vm.submit(session::signIn) }, onSignUp, { session.prefill(state.email); onReset() },
        { session.prefill(state.email); onVerification() }, onDemo, showDemo)
}

@Composable
fun LoginScreen(state: LoginUiState, returning: Boolean, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    onSubmit: () -> Unit, onSignUp: () -> Unit, onReset: () -> Unit, onVerification: () -> Unit, onDemo: () -> Unit, showDemo: Boolean = true) {
    val policy=AuthInputPolicy()
    val emailError=if(state.error == AuthError.InvalidInput && !policy.validEmail(state.email.trim())) stringResource(R.string.ui_email_invalid) else null
    val passwordError=when {
        state.error == AuthError.InvalidInput && !policy.validLoginPassword(AuthSecret(state.password)) -> stringResource(R.string.ui_password_required)
        state.error in setOf(AuthError.InvalidCredentials,AuthError.Unauthorized) -> stringResource(R.string.auth_invalid_credentials)
        else -> null
    }
    AuthColumn {
        Text(stringResource(R.string.auth_login), style = EventPassTypography.heading, modifier = Modifier.testTag("login-screen"))
        Text(stringResource(if (returning) R.string.auth_return_hint else R.string.auth_login_hint))
        FormField(state.email, onEmail, stringResource(R.string.auth_email), Modifier.testTag("auth-email"),
            enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),error=emailError)
        PasswordField(state.password, onPassword, stringResource(R.string.auth_password), Modifier.testTag("auth-password"), enabled = !state.busy,error=passwordError)
        if(emailError == null && passwordError == null) AuthErrorText(state.error)
        TextAction(stringResource(R.string.auth_forgot), onReset, enabled = !state.busy)
        EventPassButton(stringResource(R.string.auth_login), onSubmit, Modifier.fillMaxWidth().testTag("auth-submit"), loading = state.busy)
        if (state.error == AuthError.EmailUnverified) TextAction(stringResource(R.string.auth_request_verification), onVerification)
        TextAction(stringResource(R.string.auth_signup), onSignUp, enabled = !state.busy)
        Text(stringResource(R.string.auth_event_hint), style = EventPassTypography.hint)
        if(showDemo) TextAction(stringResource(R.string.auth_demo_tools), onDemo)
    }
}

@Composable
fun AccountFlowRoute(reset: Boolean, session: AppSessionViewModel, onLogin: () -> Unit, onOutbox: () -> Unit, verification: Boolean = false, showTools: Boolean = true) {
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
        { session.prefill(state.email); onLogin() }, onOutbox, showTools)
}

@Composable
fun AccountFlowScreen(s: AccountUiState, reset: Boolean, cooldown: Long,
    onEmail: (String) -> Unit, onName: (String) -> Unit, onPassword: (String) -> Unit,
    onCode: (String) -> Unit, onConsent: (Boolean) -> Unit, onSubmit: () -> Unit,
    onResend: () -> Unit, onLogin: () -> Unit, onOutbox: () -> Unit, showTools: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val policy=AuthInputPolicy()
    val invalid=s.error == AuthError.InvalidInput && s.step == AccountStep.Input
    val nameError=if(invalid && !reset && !policy.validName(s.name)) stringResource(R.string.ui_name_invalid) else null
    val emailError=when {
        invalid && !policy.validEmail(s.email.trim()) -> stringResource(R.string.ui_email_invalid)
        s.error == AuthError.EmailTaken -> stringResource(R.string.auth_email_taken)
        else -> null
    }
    val passwordError=if(invalid && !reset && !policy.validPassword(AuthSecret(s.password))) stringResource(R.string.auth_password_hint) else null
    val consentError=if(invalid && !reset && !s.consent) stringResource(R.string.ui_consent_required) else null
    fun openDocument(url: String?) {
        val uri = url?.let(android.net.Uri::parse) ?: return
        if(uri.scheme == "https" && !uri.host.isNullOrBlank()) try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) } catch(_: android.content.ActivityNotFoundException) { /* Browser may be unavailable. */ }
    }
    AuthColumn {
        Text(stringResource(if (reset) R.string.auth_reset else R.string.auth_signup), style = EventPassTypography.heading)
        when (s.step) {
            AccountStep.Input -> {
                if (!reset) FormField(s.name, onName, stringResource(R.string.auth_name), Modifier.testTag("auth-name"), enabled = !s.busy,error=nameError)
                FormField(s.email, onEmail, stringResource(R.string.auth_email), Modifier.testTag("auth-email"), enabled = !s.busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),error=emailError)
                if (!reset) {
                    PasswordField(s.password, onPassword, stringResource(R.string.auth_password), Modifier.testTag("auth-password"), enabled = !s.busy,
                        supportingText = stringResource(R.string.auth_password_hint),error=passwordError)
                    s.documents?.terms?.url?.let { TextAction(stringResource(R.string.auth_terms_link), { openDocument(it) }) }
                    s.documents?.privacy?.url?.let { TextAction(stringResource(R.string.auth_privacy_link), { openDocument(it) }) }
                    if(s.documents != null && (s.documents.terms.url == null || s.documents.privacy.url == null)) Text(stringResource(R.string.auth_documents_unavailable))
                    AuthErrorText(s.documentsError)
                    Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.tiny)) {
                        ConsentCheckbox(s.consent, onConsent, stringResource(R.string.auth_consent), Modifier.testTag("auth-consent"), enabled = !s.busy)
                        FieldMessage(consentError)
                    }
                }
                EventPassButton(stringResource(if (reset) R.string.auth_send_link else R.string.auth_signup), onSubmit,
                    Modifier.fillMaxWidth().testTag("auth-submit"), loading = s.busy)
            }
            AccountStep.Verification -> {
                Text(stringResource(if (s.sent) R.string.auth_mail_link_sent else R.string.auth_verification_pending, s.email))
                EventPassButton(stringResource(R.string.auth_resend), onSubmit, enabled = cooldown == 0L, loading = s.busy)
                TextAction(stringResource(R.string.auth_login), onLogin)
            }
            AccountStep.Done -> {
                Text(stringResource(R.string.auth_reset_done))
                EventPassButton(stringResource(R.string.auth_login), onLogin)
            }
        }
        if(listOf(nameError,emailError,passwordError,consentError).all {it == null}) AuthErrorText(s.error)
        if (cooldown > 0 && s.step in listOf(AccountStep.Verification)) Text(stringResource(R.string.auth_retry_seconds, cooldown))
        if(showTools) TextAction(stringResource(R.string.auth_outbox), onOutbox)
    }
}
