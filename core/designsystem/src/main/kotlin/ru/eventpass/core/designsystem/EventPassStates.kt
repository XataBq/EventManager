package ru.eventpass.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.padding(EventPassDimens.large), verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
        EventPassIcon(EventPassGlyph.Ticket, null, Modifier.size(EventPassDimens.avatar), EventPassColors.muted)
        Text(title, style = EventPassTypography.cardTitle)
        Text(description, style = EventPassTypography.body, color = EventPassColors.textSecondary)
        actionLabel?.let { TextAction(it, onAction) }
    }
}

@Composable
fun ErrorState(title: String, description: String, onRetry: () -> Unit, modifier: Modifier = Modifier, retryLabel: String = stringResource(R.string.ep_retry)) {
    Column(modifier.padding(EventPassDimens.large), verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
        EventPassIcon(EventPassGlyph.Error, null, Modifier.size(EventPassDimens.avatar), EventPassColors.danger)
        Text(title, style = EventPassTypography.cardTitle)
        InlineError(description)
        EventPassButton(retryLabel, onRetry, style = ActionStyle.Secondary)
    }
}

@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
        EventPassIcon(EventPassGlyph.Error, null, Modifier.size(EventPassDimens.icon), EventPassColors.danger)
        Text(text, style = EventPassTypography.hint, color = EventPassColors.danger)
    }
}

@Composable
fun ConfirmDialog(
    title: String, description: String, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = false, loading: Boolean = false,
    dismissLabel: String = stringResource(R.string.ep_cancel),
) {
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(title, style = EventPassTypography.cardTitle) },
        text = { Text(description, style = EventPassTypography.body) },
        confirmButton = { EventPassButton(confirmLabel, onConfirm, style = if (destructive) ActionStyle.Danger else ActionStyle.Primary, loading = loading) },
        dismissButton = { TextAction(dismissLabel, onDismiss, enabled = !loading) },
        shape = EventPassShapes.large,
        containerColor = EventPassColors.card,
    )
}
