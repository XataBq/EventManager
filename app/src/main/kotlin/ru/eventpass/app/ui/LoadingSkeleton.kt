package ru.eventpass.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.eventpass.app.R
import ru.eventpass.core.designsystem.EventPassColors
import ru.eventpass.core.designsystem.EventPassShapes

internal enum class LoadingLayout { Tickets, Events, Profile, Event, Form, Dashboard, List, Line, Code }

@Composable
internal fun LoadingSkeleton(layout: LoadingLayout, modifier: Modifier = Modifier) {
    val announcement = stringResource(R.string.loading)
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeleton opacity",
    )
    Column(
        modifier.fillMaxWidth().testTag("loading-${layout.name}")
            .clearAndSetSemantics { contentDescription = announcement }
            .graphicsLayer { alpha = pulse },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (layout) {
            LoadingLayout.Tickets -> repeat(2) { SkeletonCard(height = 86.dp, marker = true) }
            LoadingLayout.Events -> repeat(2) { SkeletonCard(height = 100.dp) }
            LoadingLayout.Profile -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).background(EventPassColors.raised, EventPassShapes.large))
                    SkeletonBlock(14.dp, width = 0.34f)
                }
                repeat(2) { SkeletonBlock(52.dp) }
            }
            LoadingLayout.Event -> {
                SkeletonBlock(25.dp, width = 0.66f)
                SkeletonBlock(12.dp, width = 0.46f)
                SkeletonBlock(116.dp)
            }
            LoadingLayout.Form -> {
                SkeletonBlock(52.dp)
                SkeletonBlock(84.dp)
            }
            LoadingLayout.Dashboard -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { SkeletonBlock(56.dp, modifier = Modifier.weight(1f)) }
                }
                SkeletonCard(height = 72.dp)
            }
            LoadingLayout.List -> repeat(2) { SkeletonCard(height = 64.dp, marker = true) }
            LoadingLayout.Line -> SkeletonBlock(12.dp, width = 0.44f)
            LoadingLayout.Code -> SkeletonBlock(110.dp, width = 0.64f, color = Color(0xFFE2E3EB),
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun SkeletonCard(height: Dp, marker: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().height(height).background(EventPassColors.surface, EventPassShapes.large).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marker) Box(Modifier.size(32.dp).background(EventPassColors.raised, EventPassShapes.control))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(14.dp, width = 0.65f)
            SkeletonBlock(10.dp, width = 0.38f)
        }
    }
}

@Composable
private fun SkeletonBlock(height: Dp, width: Float = 1f, modifier: Modifier = Modifier, color: Color = EventPassColors.raised) {
    Box(modifier.fillMaxWidth(width).height(height).background(color, EventPassShapes.section))
}
