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
        if (!AuthInputPolicy().validEmail(s.email.trim()) || !AuthInputPolicy().validLoginPassword(AuthSecret(s.password))) {
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

enum class AccountStep { Input, Verification, Done }
data class AccountUiState(val email: String = "", val name: String = "", val password: String = "", val code: String = "", val consent: Boolean = false, val step: AccountStep = AccountStep.Input, val busy: Boolean = false, val error: AuthError? = null, val resendAt: Instant? = null, val sent: Boolean = false, val documents: ConsentDocuments? = null, val documentsError: AuthError? = null)
@HiltViewModel
class AccountFlowViewModel @Inject constructor(private val auth: AuthRepository, private val saved: SavedStateHandle, private val clock: Clock) : ViewModel() {
 private val mutable = MutableStateFlow(AccountUiState(email = saved["email"] ?: ""))
 val state = mutable.asStateFlow()
 init { viewModelScope.launch { when(val r=auth.consentDocuments()) { is AuthResult.Success -> mutable.value=mutable.value.copy(documents=r.value); is AuthResult.Failed -> mutable.value=mutable.value.copy(documentsError=r.error) } } }
 private var verificationOnly = false
 fun now() = clock.instant()
 fun edit(email: String = mutable.value.email, name: String = mutable.value.name, password: String = mutable.value.password, code: String = "", consent: Boolean = mutable.value.consent) {
  saved["email"] = email
  mutable.value = mutable.value.copy(email=email,name=name,password=password,consent=consent,error=null)
 }
 fun verification(email: String) { if(!verificationOnly) { verificationOnly=true; edit(email=email); mutable.value=mutable.value.copy(step=AccountStep.Verification); submit(false) } }
 fun submit(reset: Boolean) {
  val s=mutable.value
  if(s.busy) return
  if(!AuthInputPolicy().validEmail(s.email.trim()) || (!reset && !verificationOnly && (!s.consent || !AuthInputPolicy().validName(s.name) || !AuthInputPolicy().validPassword(AuthSecret(s.password))))) { mutable.value=s.copy(error=AuthError.InvalidInput); return }
  mutable.value=s.copy(busy=true,error=null)
  viewModelScope.launch {
   try {
    if(reset || verificationOnly) {
     when(val r=if(reset) auth.requestPasswordReset(s.email.trim()) else auth.requestVerification(s.email.trim())) {
      is AuthResult.Failed -> mutable.value=mutable.value.copy(error=r.error,resendAt=(r.error as? AuthError.Cooldown)?.retryAt)
      is AuthResult.Success -> mutable.value=mutable.value.copy(step=AccountStep.Verification,sent=true,password="",resendAt=r.value.resendAt)
     }
    } else when(val r=auth.signUp(s.name.trim(),s.email.trim(),AuthSecret(s.password),s.consent)) {
     is AuthResult.Failed -> mutable.value=mutable.value.copy(error=r.error)
     is AuthResult.Success -> mutable.value=mutable.value.copy(step=AccountStep.Done,password="")
    }
   } finally { mutable.value=mutable.value.copy(busy=false) }
  }
 }
 fun resendReset() { submit(true) }
}
