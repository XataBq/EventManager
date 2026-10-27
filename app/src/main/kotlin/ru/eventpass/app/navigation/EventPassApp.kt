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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import ru.eventpass.app.R
import ru.eventpass.app.feature.demo.DemoRoute
import ru.eventpass.core.platform.DemoLinkParser
import ru.eventpass.app.feature.gallery.ComponentGallery
import ru.eventpass.core.designsystem.*

data class LinkRequest(val uri: String?, val requestId: String)

@Composable
fun EventPassApp(linkRequest: LinkRequest?, onLinkConsumed: () -> Unit) {
    val navigation = rememberNavigationState()
    var invalidLink by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(linkRequest) {
        linkRequest?.let { request ->
            val id = DemoLinkParser.parse(request.uri)
            if (id == null) invalidLink = true else navigation.openDemo(id)
            onLinkConsumed()
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
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<RootTab> { root -> RootScreen(root, { navigation.openDemo("demo") }, navigation::openGallery) }
                    entry<DemoDetails> { route -> DemoRoute(route.recordId, navigation::back, navigation::openGallery) }
                    entry<GalleryKey> { ComponentGallery(navigation::back) }
                },
            )
        }
    }

    BackHandler(navigation.currentStack.size == 1 && navigation.currentTab != RootTab.Tickets) {
        navigation.back()
    }
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
                onBack = navigation::back,
                onProfile = { navigation.select(RootTab.Profile) },
            )
        },
        bottomBar = {
            EventPassBottomBar(BottomDestination.valueOf(navigation.currentTab.name),
                { navigation.select(RootTab.valueOf(it.name)) })
        },
    ) { padding ->
        NavDisplay(
            entries = tabEntries.getValue(navigation.currentTab),
            onBack = navigation::back,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        )
    }
    if (invalidLink) {
        AlertDialog(
            onDismissRequest = { invalidLink = false },
            text = { Text(stringResource(R.string.invalid_link)) },
            confirmButton = {
                TextAction(stringResource(R.string.dismiss), onClick = { invalidLink = false })
            },
        )
    }
}

@Composable
private fun RootScreen(tab: RootTab, onOpenDemo: () -> Unit, onOpenGallery: () -> Unit) {
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
        if (tab == RootTab.Tickets) {
            EventPassButton(stringResource(R.string.open_demo), onOpenDemo, Modifier.testTag("open-demo"))
            EventPassButton(stringResource(R.string.open_gallery), onOpenGallery, Modifier.testTag("open-gallery"), style = ActionStyle.Secondary)
        }
    }
}
