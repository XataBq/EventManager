package ru.eventpass.app.navigation

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.*
import ru.eventpass.app.feature.profile.ProfileViewModel
import ru.eventpass.app.navigation.auth.*
import ru.eventpass.core.designsystem.*
import kotlinx.coroutines.launch

/** M01 real auth/profile surface; every entry retains and clears its own ViewModel store. */
@Composable fun EventPassApp(session: AppSessionViewModel = hiltViewModel()) {
 val scope=rememberCoroutineScope()
 val gate by session.gate.collectAsStateWithLifecycle()
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 LaunchedEffect(session,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { session.coordinator.refreshSession() } }
 when(val g=gate) {
  SessionGate.Loading -> AuthColumn { Text(stringResource(R.string.loading)) }
  is SessionGate.Recovery -> AuthColumn { AuthErrorText(g.error); EventPassButton(stringResource(R.string.remote_retry_session),{ scope.launch { session.coordinator.refreshSession() } }); TextAction(stringResource(R.string.auth_login),{ session.signOut() }) }
  is SessionGate.Shell -> key(g.scope) { RemoteProfileNavigation(session) }
  SessionGate.Login -> RemotePublicNavigation(session)
 }
}

@Composable private fun RemotePublicNavigation(session: AppSessionViewModel) {
 val stack=rememberNavBackStack(LoginKey)
 fun login() { while(stack.size>1) stack.removeLastOrNull() }
 NavDisplay(backStack=stack, onBack={ if(stack.size>1) stack.removeLastOrNull() },
  entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.publicOwner)),
  entryProvider=entryProvider {
   entry<LoginKey> { LoginRoute(session,{stack.add(SignUpKey)},{stack.add(ResetKey)},{stack.add(VerificationKey)},{},showDemo=false) }
   entry<SignUpKey> { AccountFlowRoute(false,session,::login,{},showTools=false) }
   entry<ResetKey> { AccountFlowRoute(true,session,::login,{},showTools=false) }
   entry<VerificationKey> { AccountFlowRoute(false,session,::login,{},verification=true,showTools=false) }
  })
}

@Composable private fun RemoteProfileNavigation(session: AppSessionViewModel) {
 val stack=rememberNavBackStack(RootTab.Profile)
 NavDisplay(backStack=stack,onBack={ if(stack.size>1) stack.removeLastOrNull() },
  entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.owner(RootTab.Profile))),
  entryProvider=entryProvider {
   entry<RootTab> {
    val vm: ProfileViewModel=hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) { vm.start(); onDispose { vm.stop() } }
    AuthColumn {
     Text(stringResource(R.string.remote_profile_readonly))
     state.profile?.let { Text(it.fields.name); Text(it.account.email); Text(stringResource(if(it.account.emailVerified) R.string.auth_email_verified_status else R.string.auth_email_unverified_status)) }
     TextAction(stringResource(R.string.auth_request_verification),{session.prefill(session.account?.email.orEmpty());stack.add(VerificationKey)})
     EventPassButton(stringResource(R.string.auth_logout_action),{session.signOut()})
    }
   }
   entry<VerificationKey> { AccountFlowRoute(false,session,{stack.removeLastOrNull()},{},verification=true,showTools=false) }
  })
}
