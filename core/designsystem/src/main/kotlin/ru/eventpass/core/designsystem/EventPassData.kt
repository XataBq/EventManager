package ru.eventpass.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class StatusTone { Neutral, Success, Warning, Danger }

internal fun StatusTone.color(): Color = when (this) {
    StatusTone.Neutral -> EventPassColors.muted
    StatusTone.Success -> EventPassColors.success
    StatusTone.Warning -> EventPassColors.warning
    StatusTone.Danger -> EventPassColors.danger
}

@Composable
fun StatusBadge(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    Surface(modifier, EventPassShapes.badge, tone.color().copy(alpha = EventPassColors.StatusAlpha), contentColor = tone.color()) {
        Text(text, Modifier.padding(horizontal = EventPassDimens.small, vertical = 2.dp), style = EventPassTypography.label)
    }
}

@Composable
fun StatusDot(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EventPassDimens.labelGap)) {
        Box(Modifier.size(6.dp).background(tone.color(), CircleShape))
        Text(text, style = EventPassTypography.hint, color = EventPassColors.textSecondary)
    }
}

@Composable
fun ResultMark(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val drawable = when (tone) {
        StatusTone.Success -> R.drawable.ic_success
        StatusTone.Warning -> R.drawable.ic_warning
        StatusTone.Danger -> R.drawable.ic_danger
        StatusTone.Neutral -> null
    }
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
        if (drawable != null) Image(painterResource(drawable), null)
        else EventPassIcon(EventPassGlyph.Error, null, Modifier.size(EventPassDimens.statusMark), tone.color())
        Text(text, style = EventPassTypography.emphasis, color = tone.color())
    }
}

@Composable
fun AvatarInitials(name: String, modifier: Modifier = Modifier) {
    val initials = name.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(2)
        .joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }.uppercase(Locale.getDefault())
    val description = name.ifBlank { stringResource(R.string.ep_profile) }
    Box(modifier.defaultMinSize(minWidth = EventPassDimens.avatar, minHeight = EventPassDimens.avatar)
        .background(EventPassColors.accentLight, CircleShape).padding(EventPassDimens.tiny)
        .clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (initials.isEmpty()) EventPassIcon(EventPassGlyph.Avatar, null, tint = EventPassColors.onAccent)
        else Text(initials, style = EventPassTypography.label, color = EventPassColors.onAccent)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(EventPassDimens.tiny)) {
        Text(label, style = EventPassTypography.hint, color = EventPassColors.muted)
        Text(value, style = EventPassTypography.emphasis)
    }
}

@Composable
fun EventPassSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, EventPassShapes.section, EventPassColors.card) {
        Column(Modifier.padding(EventPassDimens.large), verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium), content = content)
    }
}

@Composable
fun MetricCard(label: String, value: String, supportingText: String? = null, modifier: Modifier = Modifier) {
    EventPassSection(modifier) {
        Text(label, style = EventPassTypography.label, color = EventPassColors.textSecondary)
        Text(value, style = EventPassTypography.heading)
        supportingText?.let { Text(it, style = EventPassTypography.hint, color = EventPassColors.muted) }
    }
}
