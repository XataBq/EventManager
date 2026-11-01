package ru.eventpass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.eventpass.app.R
import ru.eventpass.app.feature.demo.DemoRoute
import ru.eventpass.app.feature.gallery.ComponentGallery
import ru.eventpass.core.designsystem.*

@Composable
fun PrivateShell(session: ru.eventpass.app.navigation.auth.AppSessionViewModel,
    shell: ru.eventpass.app.navigation.auth.SessionGate.Shell,
    entryProbe: @Composable (RootTab) -> Unit = {}) {
    val navigation = rememberNavigationState()
    var editorBack by androidx.compose.runtime.remember { mutableStateOf<(() -> Unit)?>(null) }
    var scannerBack by androidx.compose.runtime.remember { mutableStateOf<(() -> Unit)?>(null) }
    var accessLost by androidx.compose.runtime.remember { mutableStateOf(false) }
    fun back() { when (navigation.currentStack.lastOrNull()) {
        is EditorKey -> editorBack?.invoke() ?: navigation.back()
        is ScannerKey -> scannerBack?.invoke() ?: navigation.back()
        else -> navigation.back()
    } }
    fun denied() { accessLost = true; navigation.returnToMyEvents() }
    var selectedTicket by rememberSaveable { mutableStateOf<String?>(null) }
    val open by session.open.collectAsStateWithLifecycle()
    val forbidden by session.forbidden.collectAsStateWithLifecycle()
    LaunchedEffect(forbidden) {
        val event = forbidden as? ru.eventpass.app.navigation.auth.ReturnDestination.Event
        if (event != null && (navigation.currentStack.lastOrNull() as? EventKey)?.eventId == event.eventId) navigation.back()
    }
    LaunchedEffect(shell.scope) {
        when (val destination = session.takeDestination(shell)) {
            is ru.eventpass.app.navigation.auth.ReturnDestination.Event -> navigation.openLinkedEvent(destination.eventId)
            is ru.eventpass.app.navigation.auth.ReturnDestination.Root -> navigation.select(destination.tab)
            null -> Unit
        }
    }
    LaunchedEffect(open) {
        open?.let { request ->
            request.demoId?.let(navigation::openDemo)
            (request.destination as? ru.eventpass.app.navigation.auth.ReturnDestination.Event)?.let { navigation.openLinkedEvent(it.eventId) }
            (request.destination as? ru.eventpass.app.navigation.auth.ReturnDestination.Root)?.let { navigation.select(it.tab) }
            session.consumeOpen(request.id)
        }
    }

    // Decorate every stack even while hidden, so switching tabs does not remove its owners.
    val tabEntries = mutableMapOf<RootTab, List<NavEntry<NavKey>>>()
    for (tab in RootTab.entries) {
        key(tab) {
            tabEntries[tab] = rememberDecoratedNavEntries(
                backStack = navigation.stacks.getValue(tab),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner = session.owner(tab)),
                ),
                entryProvider = entryProvider {
                    entry<RootTab> { root ->
                        entryProbe(root)
                        if (root == RootTab.Tickets) ru.eventpass.app.feature.tickets.TicketsRoute(selectedTicket, { selectedTicket = null }, navigation::openEvent,
                            { navigation.openDemo("demo") }, navigation::openGallery, { navigation.currentStack.add(DemoToolsKey) })
                        else if (root == RootTab.Events) ru.eventpass.app.feature.myevents.MyEventsRoute(
                            { navigation.currentStack.add(EditorKey(draftId = java.util.UUID.randomUUID().toString(), create = true)) },
                            { navigation.currentStack.add(EditorKey(draftId = it)) },
                            { navigation.currentStack.add(EditorKey(eventId = it)) }, navigation::openEvent,
                            { navigation.currentStack.add(ScannerKey(it)) }, { navigation.currentStack.add(VolunteersKey(it)) })
                        else RootScreen(root, { navigation.openDemo("demo") }, navigation::openGallery,
                            session.account?.email.orEmpty(),
                            { session.signOut() }, { navigation.currentStack.add(DemoToolsKey) })
                    }
                    entry<EventKey> { route -> ru.eventpass.app.feature.event.EventRoute(route.eventId, { _, _, _ ->
                        session.guard(ru.eventpass.app.navigation.auth.AccessOutcome.Unauthenticated, ru.eventpass.app.navigation.auth.ReturnDestination.Event(route.eventId))
                    }, { id ->
                        selectedTicket = id
                        while (navigation.currentStack.size > 1) navigation.currentStack.removeLastOrNull()
                        navigation.select(RootTab.Tickets)
                    }, navigation::back) }
                    entry<EditorKey> { route -> ru.eventpass.app.feature.editor.EditorRoute(route.draftId, route.eventId, route.create,
                        navigation::back, navigation::openEvent, { editorBack = it }) }
                    entry<ScannerKey> { route -> ru.eventpass.app.feature.checkin.ScannerRoute(route.eventId,
                        navigation.currentTab == tab && navigation.currentStack.lastOrNull() == route,
                        navigation::back, ::denied, { scannerBack = it }) }
                    entry<VolunteersKey> { route -> ru.eventpass.app.feature.volunteers.VolunteersRoute(route.eventId,
                        navigation.currentTab == tab && navigation.currentStack.lastOrNull() == route, ::denied) }
                    entry<DemoToolsKey> { ru.eventpass.app.feature.tools.DemoTools(false, navigation::openGallery,
                        { navigation.openDemo("demo") }, { navigation.currentStack.add(OutboxKey) }, { session.receiveLink(it) }, session::guard) }
                    entry<OutboxKey> { ru.eventpass.app.feature.tools.DemoTools(true, {}, {}, {}, { session.receiveLink(it) }, session::guard) }
                    entry<DemoDetails> { route -> DemoRoute(route.recordId, navigation::back, navigation::openGallery) }
                    entry<GalleryKey> { ComponentGallery(navigation::back) }
                },
            )
        }
    }

    BackHandler(navigation.currentStack.size == 1 && navigation.currentTab != RootTab.Tickets) {
        navigation.back()
    }
    if (accessLost) androidx.compose.material3.AlertDialog(onDismissRequest = { accessLost = false },
        text = { Text(stringResource(R.string.scan_access_lost)) },
        confirmButton = { TextAction(stringResource(R.string.dismiss), { accessLost = false }) })
    Scaffold(
        // Protect every slot from side bars/cutouts; child bars consume only the remaining insets.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val nested = navigation.currentStack.size > 1
            EventPassTopBar(
                title = stringResource(when (navigation.currentStack.lastOrNull()) {
                    GalleryKey -> R.string.gallery_title
                    is DemoDetails -> R.string.demo_title
                    else -> R.string.brand
                }),
                subtitle = stringResource(R.string.demo_badge),
                variant = if (nested) TopBarVariant.Nested else TopBarVariant.Authenticated,
                onBack = ::back,
                onProfile = { navigation.select(RootTab.Profile) },
            )
        },
        bottomBar = {
            EventPassBottomBar(BottomDestination.valueOf(navigation.currentTab.name),
                { val tab = RootTab.valueOf(it.name)
                    if (tab == navigation.currentTab && (navigation.currentStack.lastOrNull() is EditorKey || navigation.currentStack.lastOrNull() is ScannerKey)) back()
                    else navigation.select(tab) })
        },
    ) { padding ->
        NavDisplay(
            entries = tabEntries.getValue(navigation.currentTab),
            onBack = ::back,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        )
    }
}

@Composable
private fun RootScreen(tab: RootTab, onOpenDemo: () -> Unit, onOpenGallery: () -> Unit, email: String, onSignOut: () -> Unit, onTools: () -> Unit) {
    val (title, hint) = when (tab) {
        RootTab.Tickets -> R.string.tickets_title to R.string.tickets_hint
        RootTab.Events -> R.string.events_title to R.string.events_hint
        RootTab.Profile -> R.string.profile_title to R.string.profile_hint
    }
    Column(
        modifier = Modifier.fillMaxSize().testTag("root-${tab.name}")
            .verticalScroll(rememberScrollState()).padding(EventPassDimens.page),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.page),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(hint))
        if (tab == RootTab.Profile) {
            Text(email, modifier = Modifier.testTag("profile-email"))
            EventPassButton(stringResource(R.string.auth_logout), onSignOut, Modifier.testTag("sign-out"))
        }
        TextAction(stringResource(R.string.auth_demo_tools), onTools)
        if (tab == RootTab.Tickets) {
            EventPassButton(stringResource(R.string.open_demo), onOpenDemo, Modifier.testTag("open-demo"))
            EventPassButton(stringResource(R.string.open_gallery), onOpenGallery, Modifier.testTag("open-gallery"), style = ActionStyle.Secondary)
        }
    }
}
