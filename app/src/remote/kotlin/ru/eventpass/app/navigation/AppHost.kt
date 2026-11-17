package ru.eventpass.app.navigation

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.saveable.rememberSaveable
import ru.eventpass.core.platform.PublicEventLinkParser
import ru.eventpass.app.feature.event.EventRoute
import ru.eventpass.app.feature.tickets.TicketsRoute

/** M01 real auth/profile surface; every entry retains and clears its own ViewModel store. */
@Composable fun EventPassApp(session: AppSessionViewModel = hiltViewModel()) {
 val scope=rememberCoroutineScope()
 val gate by session.gate.collectAsStateWithLifecycle()
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 LaunchedEffect(session,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { session.coordinator.refreshSession() } }
 Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) { when(val g=gate) {
  SessionGate.Loading -> AuthColumn { Text(stringResource(R.string.loading)) }
  is SessionGate.Recovery -> AuthColumn { AuthErrorText(g.error); EventPassButton(stringResource(R.string.remote_retry_session),{ scope.launch { session.coordinator.refreshSession() } }); TextAction(stringResource(R.string.auth_login),{ session.signOut() }) }
  is SessionGate.Shell -> key(g.scope) { RemoteProfileNavigation(session,g) }
  SessionGate.Login -> RemotePublicNavigation(session)
 }
 }
}

@Composable private fun RemotePublicNavigation(session: AppSessionViewModel) {
 val stack=rememberNavBackStack(LoginKey)
 fun login() { while(stack.size>1) stack.removeLastOrNull() }
 NavDisplay(backStack=stack, onBack={ if(stack.size>1) stack.removeLastOrNull() },
  entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.publicOwner)),
  entryProvider=entryProvider {
   entry<LoginKey> { Column { Box(Modifier.weight(1f)) { LoginRoute(session,{stack.add(SignUpKey)},{stack.add(ResetKey)},{stack.add(VerificationKey)},{},showDemo=false) }; PublicEventImport { stack.add(EventKey("", publicSlug = it)) } } }
   entry<EventKey> { key -> EventRoute(key.eventId, { email, signup, returning ->
       session.prefill(email)
       if(returning) session.requireLogin(ReturnDestination.Event(key.eventId,key.publicSlug)) else session.clearDestination()
       stack.add(EventAuthKey(key.flowId,if(signup) EventAuthScreen.SignUp else EventAuthScreen.Login))
   }, { stack.add(RemoteUnavailableKey) }, { stack.removeLastOrNull() }, publicSlug = key.publicSlug, visible = stack.lastOrNull() == key) }
   entry<EventAuthKey> { key ->
       when(key.screen) {
        EventAuthScreen.Login -> LoginRoute(session,{stack.add(EventAuthKey(key.flowId,EventAuthScreen.SignUp))},{stack.add(EventAuthKey(key.flowId,EventAuthScreen.Reset))},{stack.add(EventAuthKey(key.flowId,EventAuthScreen.Verification))},{},showDemo=false)
        else -> AccountFlowRoute(key.screen==EventAuthScreen.Reset,session,{stack.removeLastOrNull()},{},verification=key.screen==EventAuthScreen.Verification,showTools=false)
       }
   }
   entry<RemoteUnavailableKey> { AuthColumn { Text(stringResource(R.string.feature_unavailable)); TextAction(stringResource(R.string.dismiss),{stack.removeLastOrNull()}) } }
   entry<SignUpKey> { AccountFlowRoute(false,session,::login,{},showTools=false) }
   entry<ResetKey> { AccountFlowRoute(true,session,::login,{},showTools=false) }
   entry<VerificationKey> { AccountFlowRoute(false,session,::login,{},verification=true,showTools=false) }
  })
}

@Composable private fun RemoteProfileNavigation(session: AppSessionViewModel, gate: SessionGate.Shell) {
 val stack=rememberNavBackStack(RootTab.Profile)
 LaunchedEffect(gate.scope) { (session.takeDestination(gate) as? ReturnDestination.Event)?.let { stack.add(EventKey(it.eventId, publicSlug = it.publicSlug)) } }
 NavDisplay(backStack=stack,onBack={ if(stack.size>1) stack.removeLastOrNull() },
  entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.owner(RootTab.Profile))),
  entryProvider=entryProvider {
   entry<RootTab> { root ->
    if(root == RootTab.Events) ru.eventpass.app.feature.myevents.MyEventsRoute(
     { stack.add(EditorKey(draftId=java.util.UUID.randomUUID().toString(),create=true)) },
     { stack.add(EditorKey(draftId=it)) },{ stack.add(EditorKey(eventId=it)) },
     { stack.add(EventKey("",publicSlug=it)) },{ stack.add(RemoteUnavailableKey) },
     { stack.add(RemoteUnavailableKey) },{ stack.add(RemoteUnavailableKey) },visible=stack.lastOrNull() == root)
    else {
    val vm: ProfileViewModel=hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(vm) { vm.start(); onDispose { vm.stop() } }
    AuthColumn {
     EventPassButton(stringResource(R.string.tickets_title), { stack.add(TicketKey()) })
     EventPassButton(stringResource(R.string.events_title), { stack.add(RootTab.Events) })
     PublicEventImport { stack.add(EventKey("", publicSlug = it)) }
     Text(stringResource(R.string.remote_profile_readonly))
     state.profile?.let { Text(it.fields.name); Text(it.account.email); Text(stringResource(if(it.account.emailVerified) R.string.auth_email_verified_status else R.string.auth_email_unverified_status)) }
     TextAction(stringResource(R.string.auth_request_verification),{session.prefill(session.account?.email.orEmpty());stack.add(VerificationKey)})
     EventPassButton(stringResource(R.string.auth_logout_action),{session.signOut()})
    }
    }
   }
   entry<EditorKey> { key -> ru.eventpass.app.feature.editor.EditorRoute(key.draftId,key.eventId,key.create,
    { stack.removeLastOrNull() },{ stack.add(EventKey("",publicSlug=it)) },{},visible=stack.lastOrNull() == key) }
   entry<EventKey> { key -> EventRoute(key.eventId, { _, _, _ -> stack.add(RemoteUnavailableKey) }, { stack.add(TicketKey(it)) }, { stack.removeLastOrNull() }, publicSlug = key.publicSlug, visible = stack.lastOrNull() == key) }
   entry<TicketKey> { key -> var initial by rememberSaveable { mutableStateOf(key.ticketId) }; TicketsRoute(initial, { initial = null }, { slug -> stack.add(EventKey("", publicSlug = slug)) }, {}, {}, {}, visible = stack.lastOrNull() == key) }
   entry<RemoteUnavailableKey> { AuthColumn { Text(stringResource(R.string.feature_unavailable)); TextAction(stringResource(R.string.dismiss),{stack.removeLastOrNull()}) } }
   entry<VerificationKey> { AccountFlowRoute(false,session,{stack.removeLastOrNull()},{},verification=true,showTools=false) }
  })
}

@Composable private fun PublicEventImport(open: (String) -> Unit) {
 var input by remember { mutableStateOf("") }
 var invalid by remember { mutableStateOf(false) }
 Column(Modifier.fillMaxWidth().padding(16.dp)) {
  FormField(input, { input = it; invalid = false }, stringResource(R.string.event_link_input), error = if(invalid) stringResource(R.string.event_link_invalid) else null)
  EventPassButton(stringResource(R.string.event_link_open), { val slug = PublicEventLinkParser.parse(input); if(slug == null) invalid = true else { input = ""; open(slug.value) } })
 }
}
