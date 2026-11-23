package ru.eventpass.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay

@Composable
internal fun RootTabNavDisplay(
    tab: RootTab,
    entries: List<NavEntry<NavKey>>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showAppBar: Boolean = true,
) {
    // The caller keeps decorated stacks above the display. A different tab or app bar starts
    // a new display so the outgoing destination cannot be drawn with the incoming padding.
    key(tab, showAppBar) {
        NavDisplay(entries = entries, onBack = onBack, modifier = modifier)
    }
}
