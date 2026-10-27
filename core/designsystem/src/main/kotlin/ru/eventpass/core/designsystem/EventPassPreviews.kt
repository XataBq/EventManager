package ru.eventpass.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Actions 360", widthDp = 360)
@Preview(name = "Actions large text", widthDp = 360, fontScale = 2f)
@Composable
private fun ActionsPreview() {
    EventPassTheme {
        Surface(color = EventPassColors.background) {
            Column(Modifier.padding(EventPassDimens.page), verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
                EventPassButton(stringResource(R.string.ep_preview_action), {}, Modifier.fillMaxWidth(), icon = EventPassGlyph.TicketAction)
                EventPassButton(stringResource(R.string.ep_preview_action), {}, enabled = false)
                EventPassButton(stringResource(R.string.ep_preview_action), {}, style = ActionStyle.Secondary, loading = true)
                EventPassButton(stringResource(R.string.ep_preview_action), {}, style = ActionStyle.Danger, compact = true)
                TextAction(stringResource(R.string.ep_back), {})
            }
        }
    }
}

@Preview(name = "Fields and choices", widthDp = 390)
@Preview(name = "Fields font 1.3", widthDp = 360, fontScale = 1.3f)
@Composable
private fun FieldsPreview() {
    EventPassTheme {
        Surface(color = EventPassColors.background) {
            Column(Modifier.padding(EventPassDimens.page), verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
                FormField("", {}, stringResource(R.string.ep_preview_label), placeholder = stringResource(R.string.ep_preview_placeholder))
                FormField("", {}, stringResource(R.string.ep_preview_label), error = stringResource(R.string.ep_preview_error))
                ConsentCheckbox(true, {}, stringResource(R.string.ep_preview_consent))
                SettingsSwitch(false, {}, stringResource(R.string.ep_preview_label))
                OptionCard(stringResource(R.string.ep_preview_label), stringResource(R.string.ep_preview_consent), true, {})
            }
        }
    }
}

@Preview(name = "Status and empty", widthDp = 390)
@Composable
private fun StatusPreview() {
    EventPassTheme {
        Surface(color = EventPassColors.background) {
            Column(Modifier.padding(EventPassDimens.page), verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
                StatusBadge(stringResource(R.string.ep_preview_label), StatusTone.Success)
                StatusDot(stringResource(R.string.ep_preview_label), StatusTone.Warning)
                ResultMark(stringResource(R.string.ep_preview_error), StatusTone.Danger)
                EmptyState(stringResource(R.string.ep_preview_label), stringResource(R.string.ep_preview_consent))
            }
        }
    }
}
