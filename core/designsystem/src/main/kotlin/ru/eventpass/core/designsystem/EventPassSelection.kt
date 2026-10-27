package ru.eventpass.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

@Composable
fun ConsentCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.heightIn(min = EventPassDimens.touch).alpha(if (enabled) 1f else EventPassColors.DisabledAlpha)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange).padding(vertical = EventPassDimens.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EventPassDimens.iconGap),
    ) {
        Box(
            Modifier.size(16.dp).background(if (checked) EventPassColors.accent else EventPassColors.background, EventPassShapes.badge)
                .border(EventPassDimens.border, if (checked) EventPassColors.accent else EventPassColors.muted, EventPassShapes.badge),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) EventPassIcon(EventPassGlyph.Check, null, Modifier.size(12.dp), EventPassColors.onAccent)
        }
        Text(text, style = EventPassTypography.hint, color = EventPassColors.textSecondary)
    }
}

@Composable
fun SettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.heightIn(min = EventPassDimens.touch).alpha(if (enabled) 1f else EventPassColors.DisabledAlpha)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = EventPassDimens.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EventPassDimens.large),
    ) {
        Text(text, Modifier.weight(1f), style = EventPassTypography.body)
        Image(painterResource(if (checked) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off), null)
    }
}

@Composable
fun OptionCard(
    title: String, description: String?, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Surface(
        modifier.alpha(if (enabled) 1f else EventPassColors.DisabledAlpha)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = EventPassShapes.control,
        color = if (selected) EventPassColors.raised else EventPassColors.surface,
        border = if (selected) BorderStroke(EventPassDimens.border, EventPassColors.accent) else null,
    ) {
        Column(Modifier.heightIn(min = EventPassDimens.touch).padding(EventPassDimens.medium), verticalArrangement = Arrangement.spacedBy(EventPassDimens.tiny)) {
            Text(title, style = EventPassTypography.emphasis, color = if (selected) EventPassColors.accentLight else EventPassColors.textPrimary)
            description?.let { Text(it, style = EventPassTypography.hint, color = EventPassColors.muted) }
        }
    }
}

data class SegmentOption(val label: String, val count: Int? = null)

@Composable
fun SegmentedTabs(options: List<SegmentOption>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (options.isEmpty()) return
    Row(
        modifier.background(EventPassColors.card, EventPassShapes.section).horizontalScroll(rememberScrollState())
            .selectableGroup().padding(EventPassDimens.tiny),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier.heightIn(min = EventPassDimens.touch).selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(vertical = EventPassDimens.labelGap),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.background(if (selected) EventPassColors.raised else EventPassColors.card, EventPassShapes.control)
                        .padding(horizontal = EventPassDimens.large, vertical = EventPassDimens.small),
                    horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small),
                ) {
                    Text(option.label, style = EventPassTypography.emphasis, color = if (selected) EventPassColors.textPrimary else EventPassColors.textSecondary)
                    option.count?.let { Text(NumberFormat.getIntegerInstance().format(it), style = EventPassTypography.emphasis, color = EventPassColors.muted) }
                }
            }
        }
    }
}
