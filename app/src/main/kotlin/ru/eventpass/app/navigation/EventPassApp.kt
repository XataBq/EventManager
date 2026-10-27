package ru.eventpass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
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
                    entry<RootTab> { root -> RootScreen(root) { navigation.openDemo("demo") } }
                    entry<DemoDetails> { route -> DemoRoute(route.recordId, navigation::back) }
                },
            )
        }
    }

    BackHandler(navigation.currentStack.size == 1 && navigation.currentTab != RootTab.Tickets) {
        navigation.back()
    }
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                Text(stringResource(R.string.demo_badge), style = MaterialTheme.typography.labelLarge)
                if (navigation.currentStack.size > 1) {
                    TextButton(onClick = navigation::back, modifier = Modifier.testTag("back")) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                RootTab.entries.forEach { tab ->
                    val label = stringResource(tab.labelResource())
                    NavigationBarItem(
                        selected = navigation.currentTab == tab,
                        onClick = { navigation.select(tab) },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) },
                        modifier = Modifier.testTag("tab-${tab.name}"),
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            entries = tabEntries.getValue(navigation.currentTab),
            onBack = navigation::back,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
    if (invalidLink) {
        AlertDialog(
            onDismissRequest = { invalidLink = false },
            text = { Text(stringResource(R.string.invalid_link)) },
            confirmButton = {
                TextButton(onClick = { invalidLink = false }) { Text(stringResource(R.string.dismiss)) }
            },
        )
    }
}

private fun RootTab.labelResource(): Int = when (this) {
    RootTab.Tickets -> R.string.tickets
    RootTab.Events -> R.string.events
    RootTab.Profile -> R.string.profile
}

@Composable
private fun RootScreen(tab: RootTab, onOpenDemo: () -> Unit) {
    val (title, hint) = when (tab) {
        RootTab.Tickets -> R.string.tickets_title to R.string.tickets_hint
        RootTab.Events -> R.string.events_title to R.string.events_hint
        RootTab.Profile -> R.string.profile_title to R.string.profile_hint
    }
    Column(
        modifier = Modifier.fillMaxSize().testTag("root-${tab.name}")
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(hint))
        if (tab == RootTab.Tickets) {
            Button(onClick = onOpenDemo, modifier = Modifier.testTag("open-demo")) {
                Text(stringResource(R.string.open_demo))
            }
        }
    }
}
