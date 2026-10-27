package ru.eventpass.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

enum class ActionStyle { Primary, Secondary, Danger, Text }

@Composable
fun EventPassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ActionStyle = ActionStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    icon: EventPassGlyph? = null,
) {
    val color = when (style) {
        ActionStyle.Primary -> EventPassColors.onAccent
        ActionStyle.Danger -> EventPassColors.danger
        ActionStyle.Secondary -> EventPassColors.textPrimary
        ActionStyle.Text -> EventPassColors.accentLight
    }
    val background = when (style) {
        ActionStyle.Primary -> EventPassColors.accent
        ActionStyle.Text -> Color.Transparent
        else -> EventPassColors.raised
    }
    val loadingLabel = stringResource(R.string.ep_loading)
    val visualHeight = if (compact) EventPassDimens.compactHeight else EventPassDimens.buttonHeight
    Box(
        modifier.defaultMinSize(minWidth = EventPassDimens.touch, minHeight = EventPassDimens.touch)
            .alpha(if (enabled) 1f else EventPassColors.DisabledAlpha)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = loadingLabel },
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        Surface(
            Modifier.padding(vertical = (EventPassDimens.touch - visualHeight) / 2),
            shape = EventPassShapes.control, color = background, contentColor = color,
        ) {
            Row(
                Modifier.heightIn(min = visualHeight).padding(horizontal = EventPassDimens.large, vertical = EventPassDimens.small),
                horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
                else icon?.let { EventPassIcon(it, null, tint = color) }
                Text(text, Modifier.weight(1f, fill = false), style = EventPassTypography.emphasis)
            }
        }
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    EventPassButton(text, onClick, modifier, ActionStyle.Text, enabled, loading)
}
