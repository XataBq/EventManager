package ru.eventpass.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*

/** One nested-scroll connection above the screen's existing scrollable content. */
@Composable
fun EventPassRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    // Gesture distance is transient UI state, never a restored business command.
    val pull = remember(enabled) { PullToRefreshState() }
    val action = stringResource(R.string.refresh_action)
    val description = stringResource(if (refreshing) R.string.refresh_active else R.string.refresh_idle)
    Box(modifier.fillMaxSize().semantics {
        stateDescription = description
        liveRegion = LiveRegionMode.Polite
        if (enabled) customActions = listOf(CustomAccessibilityAction(action) {
            if (!refreshing) onRefresh()
            true
        })
    }.pullToRefresh(isRefreshing = refreshing, state = pull, enabled = enabled,
        onRefresh = { if (enabled && !refreshing) onRefresh() })) {
        content()
        if (enabled && (refreshing || pull.distanceFraction > 0f)) PullToRefreshDefaults.Indicator(
            state = pull, isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = EventPassDimens.small),
            containerColor = EventPassColors.raised, color = EventPassColors.accentLight)
    }
}
