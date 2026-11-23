package ru.eventpass.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.eventpass.app.R
import ru.eventpass.core.designsystem.EventPassColors
import ru.eventpass.core.designsystem.EventPassGlyph
import ru.eventpass.core.designsystem.EventPassIcon

private const val MaxLaunchWaitMillis = 2500L
private val LocalInitialContentReady = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The first visible destination keeps its normal lifecycle while the launch cover is drawn. */
@Composable
internal fun AppLaunch(sessionOnly: Boolean, content: @Composable () -> Unit) {
    var finished by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sessionOnly) { if (sessionOnly) finished = true }
    LaunchedEffect(Unit) {
        if (!finished) {
            delay(MaxLaunchWaitMillis)
            finished = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        val covered = if (finished) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
        Box(Modifier.fillMaxSize().then(covered)) {
            CompositionLocalProvider(LocalInitialContentReady provides if (finished) null else ({ finished = true })) {
                content()
            }
        }
        AnimatedVisibility(visible = !finished, enter = EnterTransition.None, exit = fadeOut(tween(180))) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF303060), Color(0xFF191B32), EventPassColors.background)))
                    .testTag("app-launch")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                EventPassIcon(EventPassGlyph.Brand, stringResource(R.string.app_name),
                    Modifier.size(width = 104.dp, height = 83.dp), EventPassColors.accentLight)
            }
        }
    }
}

@Composable
internal fun ReportInitialContent(ready: Boolean) {
    val complete = LocalInitialContentReady.current
    if (ready && complete != null) LaunchedEffect(Unit) { complete() }
}
