package ru.eventpass.app.navigation

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Scaffold
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
import ru.eventpass.app.navigation.auth.*
import ru.eventpass.core.designsystem.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import ru.eventpass.core.platform.PublicEventLinkParser
import ru.eventpass.app.feature.event.EventRoute
import ru.eventpass.app.feature.tickets.TicketsRoute

/** Remote auth gate and account-owned navigation; feature screens remain shared with demo. */
@Composable fun EventPassApp(session: AppSessionViewModel = hiltViewModel()) {
 val scope=rememberCoroutineScope()
 val gate by session.gate.collectAsStateWithLifecycle()
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 LaunchedEffect(session,lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { session.coordinator.refreshSession() } }
 Box(Modifier.fillMaxSize()) { when(val g=gate) {
  SessionGate.Loading -> Box(Modifier.safeDrawingPadding()) {AuthColumn { Text(stringResource(R.string.loading)) }}
  is SessionGate.Recovery -> Box(Modifier.safeDrawingPadding().imePadding()) {AuthColumn { AuthErrorText(g.error); EventPassButton(stringResource(R.string.remote_retry_session),{ scope.launch { session.coordinator.refreshSession() } }); TextAction(stringResource(R.string.auth_login),{ session.signOut() }) }}
  is SessionGate.Shell -> key(g.scope) { RemotePrivateNavigation(session,g) }
  SessionGate.Login -> Box(Modifier.safeDrawingPadding()) { RemotePublicNavigation(session) }
 }
 }
}

@Composable private fun RemotePublicNavigation(session: AppSessionViewModel) {
 val stack=rememberNavBackStack(LoginKey)
 fun login() { while(stack.size>1) stack.removeLastOrNull() }
 NavDisplay(backStack=stack, onBack={ if(stack.size>1) stack.removeLastOrNull() },
  entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.publicOwner)),
  entryProvider=entryProvider {
   entry<LoginKey> {
       RemoteLoginLanding(onEvent={stack.add(EventKey("",publicSlug=it))}) {
           LoginRoute(session,{stack.add(SignUpKey)},{stack.add(ResetKey)},{stack.add(VerificationKey)},{},showDemo=false)
       }
   }
   entry<EventKey> { key -> Box(Modifier.imePadding()) {EventRoute(key.eventId, { email, signup, returning ->
       session.prefill(email)
       if(returning) session.requireLogin(ReturnDestination.Event(key.eventId,key.publicSlug)) else session.clearDestination()
       stack.add(EventAuthKey(key.flowId,if(signup) EventAuthScreen.SignUp else EventAuthScreen.Login))
   }, { session.requireLogin(ReturnDestination.Event(key.eventId,key.publicSlug));stack.add(EventAuthKey(key.flowId,EventAuthScreen.Login)) }, { stack.removeLastOrNull() }, publicSlug = key.publicSlug, visible = stack.lastOrNull() == key)} }
   entry<EventAuthKey> { key -> Box(Modifier.imePadding()) {
       when(key.screen) {
        EventAuthScreen.Login -> LoginRoute(session,{stack.add(EventAuthKey(key.flowId,EventAuthScreen.SignUp))},{stack.add(EventAuthKey(key.flowId,EventAuthScreen.Reset))},{stack.add(EventAuthKey(key.flowId,EventAuthScreen.Verification))},{},showDemo=false)
        else -> AccountFlowRoute(key.screen==EventAuthScreen.Reset,session,{stack.removeLastOrNull()},{},verification=key.screen==EventAuthScreen.Verification,showTools=false)
       }
   } }
   entry<SignUpKey> { Box(Modifier.imePadding()) {AccountFlowRoute(false,session,::login,{},showTools=false)} }
   entry<ResetKey> { Box(Modifier.imePadding()) {AccountFlowRoute(true,session,::login,{},showTools=false)} }
   entry<VerificationKey> { Box(Modifier.imePadding()) {AccountFlowRoute(false,session,::login,{},verification=true,showTools=false)} }
  })
}

/** The event shortcut stays at the screen bottom; the IME only resizes the login form. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RemoteLoginLanding(onEvent:(String)->Unit, login:@Composable ()->Unit) {
 var dialogOpen by remember {mutableStateOf(false)}
 val keyboardVisible=WindowInsets.isImeVisible
 Box(Modifier.fillMaxSize()) {
  Box(Modifier.fillMaxSize().padding(bottom=if(keyboardVisible) 0.dp else 64.dp).imePadding()) {login()}
  // The keyboard covers this fixed footer rather than lifting it above the form.
  if(!keyboardVisible) TextAction(stringResource(R.string.event_link_enter),{dialogOpen=true},
   Modifier.align(Alignment.BottomCenter).padding(horizontal=24.dp,vertical=8.dp).testTag("event-link-shortcut"))
 }
 if(dialogOpen) {
  var input by remember {mutableStateOf("")}
  var invalid by remember {mutableStateOf(false)}
  androidx.compose.material3.AlertDialog(onDismissRequest={dialogOpen=false},
   modifier=Modifier.testTag("event-link-dialog"),
   title={Text(stringResource(R.string.event_link_enter))},
   text={FormField(input,{input=it;invalid=false},stringResource(R.string.event_link_input),
    Modifier.testTag("event-link-input"),error=if(invalid) stringResource(R.string.event_link_invalid) else null)},
   confirmButton={TextAction(stringResource(R.string.event_link_open),{
    val slug=PublicEventLinkParser.parse(input)
    if(slug==null) invalid=true else {dialogOpen=false;onEvent(slug.value)}
   },Modifier.testTag("event-link-confirm"))},
   dismissButton={TextAction(stringResource(R.string.event_link_cancel),{dialogOpen=false},Modifier.testTag("event-link-cancel"))})
 }
}

/** Three independent account-owned stacks. Hidden entries retain state but stop their readers. */
@Composable private fun RemotePrivateNavigation(session: AppSessionViewModel, gate: SessionGate.Shell) {
 val navigation=rememberNavigationState()
 var editorBack by remember { mutableStateOf<(() -> Unit)?>(null) }
 var scannerBack by remember { mutableStateOf<(() -> Unit)?>(null) }
 var profileLeave by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
 var accessLost by remember { mutableStateOf(false) }
 fun open(slug:String) {navigation.currentStack.add(EventKey("",publicSlug=slug))}
 fun select(tab:RootTab) {
  if(tab==navigation.currentTab) when(navigation.currentStack.lastOrNull()) {
   is EditorKey -> {editorBack?.invoke() ?: navigation.back();return}
   is ScannerKey -> {scannerBack?.invoke() ?: navigation.back();return}
   else -> Unit
  }
  val action={navigation.select(tab)}
  if(navigation.currentTab==RootTab.Profile && tab!=RootTab.Profile) profileLeave?.invoke(action) ?: action() else action()
 }
 fun back() {when(navigation.currentStack.lastOrNull()) {
  is EditorKey -> editorBack?.invoke() ?: navigation.back()
  is ScannerKey -> scannerBack?.invoke() ?: navigation.back()
  else -> navigation.back()
 }}
 fun denied() {accessLost=true;navigation.returnToMyEvents()}
 LaunchedEffect(gate.scope) {when(val destination=session.takeDestination(gate)) {
  is ReturnDestination.Event -> {navigation.select(RootTab.Tickets);navigation.currentStack.add(EventKey(destination.eventId,publicSlug=destination.publicSlug))}
  is ReturnDestination.Root -> navigation.select(destination.tab)
  null -> Unit
 }}
 val entries=mutableMapOf<RootTab,List<NavEntry<NavKey>>>()
 for(tab in RootTab.entries) key(tab) {
  val stack=navigation.stacks.getValue(tab)
  fun visible(key:NavKey)=navigation.currentTab==tab && stack.lastOrNull()==key
  entries[tab]=rememberDecoratedNavEntries(backStack=stack,
   entryDecorators=listOf(rememberSaveableStateHolderNavEntryDecorator(),rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner=session.owner(tab))),
   entryProvider=entryProvider {
    entry<RootTab> {root->when(root) {
     RootTab.Tickets -> Column(Modifier.fillMaxSize()) {
      TextAction(stringResource(R.string.event_link_open),{stack.add(PublicEventImportKey)},Modifier.padding(horizontal=16.dp))
      Box(Modifier.weight(1f)) {TicketsRoute(null,{},::open,{},{},{},visible(root))}
     }
     RootTab.Events -> ru.eventpass.app.feature.myevents.MyEventsRoute(
      {stack.add(EditorKey(draftId=java.util.UUID.randomUUID().toString(),create=true))},
      {stack.add(EditorKey(draftId=it))},{stack.add(EditorKey(eventId=it))},::open,
      {stack.add(ScannerKey(it))},{stack.add(VolunteersKey(it))},{stack.add(DashboardKey(it))},
      visible=visible(root),onRemoteDraft={stack.add(EditorKey(remoteDraftId=it))})
     RootTab.Profile -> Column(Modifier.fillMaxSize()) {
      TextAction(stringResource(R.string.auth_request_verification),{session.prefill(session.account?.email.orEmpty());stack.add(VerificationKey)},Modifier.padding(horizontal=16.dp))
      Box(Modifier.weight(1f)) {ru.eventpass.app.feature.profile.ProfileRoute(visible(root),navigation::back,{profileLeave=it},{session.signOut()},{},showTools=false,enableDeletion=true)}
     }
    }}
    entry<PublicEventImportKey> {PublicEventImport(::open)}
    entry<EditorKey> {route->ru.eventpass.app.feature.editor.EditorRoute(route.draftId,route.eventId,route.create,navigation::back,::open,{editorBack=it},visible(route),route.remoteDraftId)}
    entry<EventKey> {route->EventRoute(route.eventId,{_,_,_->session.guard(AccessOutcome.Unauthenticated,ReturnDestination.Event(route.eventId,route.publicSlug))},
     {stack.add(TicketKey(it))},navigation::back,publicSlug=route.publicSlug,visible=visible(route))}
    entry<TicketKey> {route->var initial by rememberSaveable {mutableStateOf(route.ticketId)}
     TicketsRoute(initial,{initial=null},::open,{},{},{},visible(route),onCardClosed=navigation::back)}
    entry<ScannerKey> {route->ru.eventpass.app.feature.checkin.ScannerRoute(route.eventId,visible(route),navigation::back,::denied,{scannerBack=it})}
    entry<VolunteersKey> {route->ru.eventpass.app.feature.volunteers.VolunteersRoute(route.eventId,visible(route),::denied)}
    entry<DashboardKey> {route->ru.eventpass.app.feature.dashboard.DashboardRoute(route.eventId,visible(route),
     {stack.add(ScannerKey(route.eventId))},{stack.add(VolunteersKey(route.eventId))},::denied,exportEnabled=true)}
    entry<VerificationKey> {AccountFlowRoute(false,session,navigation::back,{},verification=true,showTools=false)}
   })
 }
 BackHandler(navigation.currentStack.size==1 && navigation.currentTab!=RootTab.Tickets) {back()}
 if(accessLost) androidx.compose.material3.AlertDialog(onDismissRequest={accessLost=false},text={Text(stringResource(R.string.scan_access_lost))},
  confirmButton={TextAction(stringResource(R.string.dismiss),{accessLost=false})})
 Scaffold(modifier=Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),contentWindowInsets=WindowInsets(0),
  topBar={EventPassTopBar(stringResource(R.string.brand),variant=if(navigation.currentStack.size>1) TopBarVariant.Nested else TopBarVariant.Authenticated,
   onBack=::back,onProfile={select(RootTab.Profile)})},
  bottomBar={EventPassBottomBar(BottomDestination.valueOf(navigation.currentTab.name),{select(RootTab.valueOf(it.name))})}) {padding->
   NavDisplay(entries=entries.getValue(navigation.currentTab),onBack=::back,modifier=Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
  }
}

@Composable private fun PublicEventImport(open: (String) -> Unit) {
 var input by remember { mutableStateOf("") }
 var invalid by remember { mutableStateOf(false) }
 Column(Modifier.fillMaxWidth().padding(16.dp)) {
  FormField(input, { input = it; invalid = false }, stringResource(R.string.event_link_input), error = if(invalid) stringResource(R.string.event_link_invalid) else null)
  EventPassButton(stringResource(R.string.event_link_open), { val slug = PublicEventLinkParser.parse(input); if(slug == null) invalid = true else { input = ""; open(slug.value) } })
 }
}
