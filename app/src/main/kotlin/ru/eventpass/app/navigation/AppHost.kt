package ru.eventpass.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.collectLatest
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.*
import ru.eventpass.app.feature.demo.DemoRoute
import ru.eventpass.app.feature.gallery.ComponentGallery
import ru.eventpass.app.feature.tools.DemoTools
import ru.eventpass.app.navigation.auth.*
import ru.eventpass.core.designsystem.*

@Composable
fun EventPassApp(session: AppSessionViewModel = hiltViewModel(), entryProbe: @Composable (RootTab) -> Unit = {}) {
    val gate by session.gate.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    val forbidden by session.forbidden.collectAsStateWithLifecycle()
    var loadingTools by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(session, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.coordinator.refreshSession()
            session.gate.collectLatest { session.awaitExpiry() }
        }
    }
    when (val value = gate) {
        is SessionGate.Shell -> if (session.isCurrent(value.scope)) key(value.scope) { PrivateShell(session, value, entryProbe) }
            else Box(Modifier.safeDrawingPadding()) { Text(stringResource(R.string.loading)) }
        SessionGate.Login -> PublicNavigation(session)
        SessionGate.Loading -> Box(Modifier.safeDrawingPadding().imePadding()) {
            if (loadingTools) DemoTools(false, {}, {}, {}, { session.receiveLink(it) }, session::guard)
            else AuthColumn {
                Text(stringResource(R.string.loading))
                TextAction(stringResource(R.string.auth_demo_tools), { loadingTools = true })
            }
        }
    }
    if (forbidden != null) AlertDialog(onDismissRequest = session::dismissForbidden,
        text = { Text(stringResource(R.string.auth_forbidden)) },
        confirmButton = { TextAction(stringResource(R.string.dismiss), session::dismissForbidden) })
    if (link.busy || link.result is AuthLinkResult.Failed || link.result is AuthLinkResult.EmailConfirmed || link.result == AuthLinkResult.Invalid) {
        AlertDialog(onDismissRequest = { if (!link.busy) session.dismissLink() }, text = {
            when (val result = link.result) {
                is AuthLinkResult.Failed -> AuthErrorText(result.error)
                is AuthLinkResult.EmailConfirmed -> Text(stringResource(R.string.auth_confirmed))
                AuthLinkResult.Invalid -> Text(stringResource(R.string.invalid_link))
                else -> Text(stringResource(R.string.loading))
            }
        }, confirmButton = {
            if (link.result is AuthLinkResult.Failed) TextAction(stringResource(R.string.auth_retry_link), session::retryLink)
            else TextAction(stringResource(R.string.dismiss), session::dismissLink, enabled = !link.busy)
        }, dismissButton = {
            if (link.result is AuthLinkResult.Failed) TextAction(stringResource(R.string.dismiss), session::dismissLink)
        })
    }
}

@Composable
private fun PublicNavigation(session: AppSessionViewModel) {
    val stack = rememberNavBackStack(LoginKey)
    val open by session.open.collectAsStateWithLifecycle()
    val link by session.link.collectAsStateWithLifecycle()
    fun pop() { if (stack.size > 1) stack.removeLastOrNull() }
    fun login() { while (stack.size > 1) stack.removeLastOrNull() }
    LaunchedEffect(link.result) { if (link.result is AuthLinkResult.EmailConfirmed) login() }
    LaunchedEffect(open) {
        open?.let { request ->
            val target: NavKey = request.demoId?.let { DemoDetails(it) }
                ?: (request.destination as? ReturnDestination.Event)?.let { EventKey(it.eventId) } ?: LoginKey
            val sameDemo = target is DemoDetails && (stack.lastOrNull() as? DemoDetails)?.recordId == target.recordId
            if (target == LoginKey) login()
            else if (!sameDemo && stack.lastOrNull() != target) stack.add(target)
            session.consumeOpen(request.id)
        }
    }
    androidx.compose.material3.Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),
        contentWindowInsets = WindowInsets(0),
        topBar = { EventPassTopBar(variant = if (stack.size > 1) TopBarVariant.Nested else TopBarVariant.Guest,
            subtitle = stringResource(R.string.demo_badge), onBack = ::pop) },
    ) { padding ->
        NavDisplay(backStack = stack, onBack = ::pop,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = session.publicOwner)),
            entryProvider = entryProvider {
                entry<LoginKey> { LoginRoute(session, { stack.add(SignUpKey) }, { stack.add(ResetKey) },
                    { stack.add(VerificationKey) }, { stack.add(DemoToolsKey) }) }
                entry<SignUpKey> { AccountFlowRoute(false, session, ::login, { stack.add(OutboxKey) }) }
                entry<VerificationKey> { AccountFlowRoute(false, session, ::login, { stack.add(OutboxKey) }, verification = true) }
                entry<ResetKey> { AccountFlowRoute(true, session, ::login, { stack.add(OutboxKey) }) }
                entry<DemoToolsKey> { DemoTools(false, { stack.add(GalleryKey) }, { stack.add(DemoDetails("demo")) },
                    { stack.add(OutboxKey) }, { session.receiveLink(it) }, session::guard) }
                entry<OutboxKey> { DemoTools(true, {}, {}, {}, session::receiveLink) }
                entry<GalleryKey> { ComponentGallery(::pop) }
                entry<DemoDetails> { DemoRoute(it.recordId, ::pop, { stack.add(GalleryKey) }) }
                entry<EventKey> { route -> EventReceiver(route.eventId, false) {
                    session.requireLogin(ReturnDestination.Event(route.eventId)); login()
                } }
            })
    }
}

@Composable
fun EventReceiver(eventId: String, authenticated: Boolean, onLogin: () -> Unit = {}) {
    AuthColumn {
        Text(stringResource(R.string.auth_event_receiver, eventId), style = EventPassTypography.heading)
        Text(stringResource(R.string.auth_event_unfinished))
        if (!authenticated) EventPassButton(stringResource(R.string.auth_login), onLogin)
    }
}
