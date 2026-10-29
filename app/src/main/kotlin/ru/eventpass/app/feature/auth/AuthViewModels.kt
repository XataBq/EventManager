package ru.eventpass.app.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.eventpass.core.domain.auth.*

data class LoginUiState(val email: String = "", val password: String = "", val busy: Boolean = false, val error: AuthError? = null)
@HiltViewModel
class LoginViewModel @Inject constructor(private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(LoginUiState(email = saved["email"] ?: ""))
    val state = mutable.asStateFlow()
    fun email(value: String) { saved["email"] = value; mutable.value = mutable.value.copy(email = value, error = null) }
    fun password(value: String) { mutable.value = mutable.value.copy(password = value, error = null) }
    fun submit(signIn: suspend (String, AuthSecret) -> AuthResult<Unit>) {
        val s = mutable.value
        if (s.busy) return
        if (!AuthInputPolicy().validEmail(s.email.trim()) || !AuthInputPolicy().validPassword(AuthSecret(s.password))) {
            mutable.value = s.copy(error = AuthError.InvalidInput); return
        }
        mutable.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = signIn(s.email.trim(), AuthSecret(s.password))) {
                    is AuthResult.Failed -> mutable.value = mutable.value.copy(error = result.error)
                    is AuthResult.Success -> mutable.value = mutable.value.copy(password = "")
                }
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

enum class AccountStep { Input, Verification, Code, Password, Done }
data class AccountUiState(
    val email: String = "", val name: String = "", val password: String = "", val code: String = "",
    val consent: Boolean = false, val step: AccountStep = AccountStep.Input,
    val busy: Boolean = false, val error: AuthError? = null, val resendAt: Instant? = null, val sent: Boolean = false,
)
/** One entry owns a sign-up OR reset flow. Secrets survive rotation in memory, never SavedState. */
@HiltViewModel
class AccountFlowViewModel @Inject constructor(
    private val auth: AuthRepository, private val saved: SavedStateHandle, private val clock: Clock,
) : ViewModel() {
    private var challenge: EmailChallenge? = null
    private var grant: AuthSecret? = null
    private val mutable = MutableStateFlow(AccountUiState(email = saved["email"] ?: ""))
    val state = mutable.asStateFlow()
    fun edit(email: String = mutable.value.email, name: String = mutable.value.name,
        password: String = mutable.value.password, code: String = mutable.value.code,
        consent: Boolean = mutable.value.consent) {
        saved["email"] = email
        mutable.value = mutable.value.copy(email = email, name = name, password = password, code = code, consent = consent, error = null)
    }
    fun now(): Instant = clock.instant()
    fun verification(email: String) {
        if (mutable.value.step == AccountStep.Input) {
            edit(email = email)
            mutable.value = mutable.value.copy(step = AccountStep.Verification)
            submit(false)
        }
    }
    fun submit(reset: Boolean) {
        val s = mutable.value
        if (s.busy) return
        val valid = when (s.step) {
            AccountStep.Input -> AuthInputPolicy().validEmail(s.email.trim()) && (reset ||
                (s.consent && AuthInputPolicy().validName(s.name) && AuthInputPolicy().validPassword(AuthSecret(s.password))))
            AccountStep.Code -> s.code.matches(Regex("[0-9]{6}"))
            AccountStep.Password -> AuthInputPolicy().validPassword(AuthSecret(s.password))
            else -> true
        }
        if (!valid) { mutable.value = s.copy(error = AuthError.InvalidInput); return }
        mutable.value = s.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                when (s.step) {
                    AccountStep.Input, AccountStep.Verification -> {
                        val result = if (reset) auth.requestPasswordResetCode(s.email.trim())
                            else if (s.step == AccountStep.Verification) auth.requestVerification(s.email.trim())
                            else auth.signUp(s.name.trim(), s.email.trim(), AuthSecret(s.password))
                        when (result) {
                            is AuthResult.Failed -> mutable.value = mutable.value.copy(error = result.error,
                                resendAt = (result.error as? AuthError.Cooldown)?.retryAt ?: mutable.value.resendAt)
                            is AuthResult.Success -> {
                                challenge = result.value
                                mutable.value = mutable.value.copy(step = if (reset) AccountStep.Code else AccountStep.Verification,
                                    password = "", code = "", resendAt = result.value.resendAt, sent = true)
                            }
                        }
                    }
                    AccountStep.Code -> {
                        val id = challenge?.id
                        if (id == null) mutable.value = mutable.value.copy(step = AccountStep.Input, error = AuthError.ExpiredChallenge)
                        else when (val result = auth.verifyResetCode(id, AuthSecret(s.code))) {
                            is AuthResult.Failed -> mutable.value = mutable.value.copy(error = result.error)
                            is AuthResult.Success -> { grant = result.value; mutable.value = mutable.value.copy(step = AccountStep.Password, code = "") }
                        }
                    }
                    AccountStep.Password -> {
                        val token = grant
                        if (token == null) mutable.value = mutable.value.copy(step = AccountStep.Input, error = AuthError.ExpiredChallenge)
                        else when (val result = auth.resetPassword(token, AuthSecret(s.password))) {
                            is AuthResult.Failed -> mutable.value = mutable.value.copy(error = result.error)
                            is AuthResult.Success -> { grant = null; mutable.value = mutable.value.copy(step = AccountStep.Done, password = "") }
                        }
                    }
                    AccountStep.Done -> Unit
                }
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun resendReset() { mutable.value = mutable.value.copy(step = AccountStep.Input, code = ""); submit(true) }
}
