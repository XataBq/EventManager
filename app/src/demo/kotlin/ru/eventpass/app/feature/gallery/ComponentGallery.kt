package ru.eventpass.app.feature.gallery

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import ru.eventpass.app.R
import ru.eventpass.core.designsystem.*

/** Demo-only interactive compositions, deliberately independent from repositories and business roles. */
@Composable
fun ComponentGallery(onClose: () -> Unit) {
    var group by rememberSaveable { mutableIntStateOf(0) }
    val groups = listOf(R.string.gallery_foundation, R.string.gallery_actions, R.string.gallery_fields,
        R.string.gallery_selection, R.string.gallery_data, R.string.gallery_states, R.string.gallery_navigation)
    Column(Modifier.fillMaxSize().testTag("component-gallery")) {
        SegmentedTabs(groups.map { SegmentOption(stringResource(it)) }, group, { group = it },
            Modifier.fillMaxWidth().testTag("gallery-groups"))
        val scroll = rememberSaveable(group, saver = ScrollState.Saver) { ScrollState(0) }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(EventPassDimens.page),
            verticalArrangement = Arrangement.spacedBy(EventPassDimens.page)) {
            Text(stringResource(R.string.gallery_notice), style = EventPassTypography.hint, color = EventPassColors.muted)
            when (group) {
                0 -> FoundationExamples()
                1 -> ActionExamples()
                2 -> FieldExamples()
                3 -> SelectionExamples()
                4 -> DataExamples()
                5 -> StateExamples()
                6 -> NavigationExamples()
            }
            TextAction(stringResource(R.string.gallery_close), onClose, Modifier.testTag("close-gallery"))
        }
    }
}

@Composable
private fun GallerySection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
        Text(stringResource(title), style = EventPassTypography.cardTitle)
        content()
    }
}

@Composable
private fun FoundationExamples() {
    Text(stringResource(R.string.gallery_title_example), style = EventPassTypography.heading)
    Text(stringResource(R.string.gallery_card_example), style = EventPassTypography.cardTitle)
    Text(stringResource(R.string.gallery_body_example), style = EventPassTypography.body)
    Text(stringResource(R.string.gallery_label_example), style = EventPassTypography.label)
    val colors = listOf(
        R.string.color_background to EventPassColors.background, R.string.color_card to EventPassColors.card,
        R.string.color_surface to EventPassColors.surface, R.string.color_raised to EventPassColors.raised,
        R.string.color_accent to EventPassColors.accent, R.string.color_accent_light to EventPassColors.accentLight,
        R.string.color_on_accent to EventPassColors.onAccent, R.string.color_text to EventPassColors.textPrimary,
        R.string.color_secondary to EventPassColors.textSecondary, R.string.color_muted to EventPassColors.muted,
        R.string.color_success to EventPassColors.success, R.string.color_warning to EventPassColors.warning,
        R.string.color_danger to EventPassColors.danger,
    )
    colors.forEach { (label, color) ->
        Row(horizontalArrangement = Arrangement.spacedBy(EventPassDimens.medium)) {
            Box(Modifier.size(EventPassDimens.buttonHeight).background(color, EventPassShapes.control))
            Text(stringResource(label), style = EventPassTypography.emphasis)
        }
    }
}

@Composable
private fun ActionExamples() {
    var count by rememberSaveable { mutableIntStateOf(0) }
    var loading by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.gallery_click_count, count), Modifier.testTag("action-count"))
    SettingsSwitch(loading, { loading = it }, stringResource(R.string.gallery_loading_switch))
    listOf(ActionStyle.Primary to R.string.action_primary, ActionStyle.Secondary to R.string.action_secondary,
        ActionStyle.Danger to R.string.action_danger, ActionStyle.Text to R.string.action_text).forEach { (style, label) ->
        GallerySection(label) {
            EventPassButton(stringResource(R.string.gallery_action), { count++ }, Modifier.fillMaxWidth(), style, loading = loading,
                icon = when (style) { ActionStyle.Primary -> EventPassGlyph.TicketAction; ActionStyle.Secondary -> EventPassGlyph.Download; else -> null })
            EventPassButton(stringResource(R.string.gallery_disabled), {}, Modifier.fillMaxWidth(), style, enabled = false)
            EventPassButton(stringResource(R.string.gallery_loading), {}, Modifier.fillMaxWidth(), style, loading = true)
            EventPassButton(stringResource(R.string.gallery_compact), { count++ }, style = style, compact = true)
        }
    }
}

@Composable
private fun FieldExamples() {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("ivan@example.com") }
    var password by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(true) }
    FormField(name, { name = it }, stringResource(R.string.gallery_name), Modifier.testTag("gallery-name"),
        placeholder = stringResource(R.string.gallery_name_placeholder), supportingText = stringResource(R.string.gallery_field_help))
    FormField(email, { email = it }, stringResource(R.string.gallery_email),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next))
    PasswordField(password, { password = it }, stringResource(R.string.gallery_password), Modifier.testTag("gallery-password"))
    SearchField(search, { search = it }, placeholder = stringResource(R.string.gallery_search_placeholder))
    ConsentCheckbox(showError, { showError = it }, stringResource(R.string.gallery_error_toggle))
    FormField(name, { name = it }, stringResource(R.string.gallery_error_field), error = if (showError) stringResource(R.string.gallery_field_error) else null)
    FormField(stringResource(R.string.gallery_long_email), {}, stringResource(R.string.gallery_disabled), enabled = false)
    FormField(stringResource(R.string.gallery_long_text), {}, stringResource(R.string.gallery_multiline), singleLine = false)
}

@Composable
private fun SelectionExamples() {
    var consent by rememberSaveable { mutableStateOf(false) }
    var notifications by rememberSaveable { mutableStateOf(true) }
    var choice by rememberSaveable { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ConsentCheckbox(consent, { consent = it }, stringResource(R.string.gallery_consent), Modifier.testTag("gallery-consent"))
    ConsentCheckbox(true, {}, stringResource(R.string.gallery_disabled), enabled = false)
    SettingsSwitch(notifications, { notifications = it }, stringResource(R.string.gallery_notifications), Modifier.testTag("gallery-switch"))
    SettingsSwitch(false, {}, stringResource(R.string.gallery_disabled), enabled = false)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
        OptionCard(stringResource(R.string.gallery_option_one), stringResource(R.string.gallery_option_one_body), choice == 0,
            { choice = 0 }, Modifier.fillMaxWidth().testTag("gallery-option-one"))
        OptionCard(stringResource(R.string.gallery_option_two), stringResource(R.string.gallery_long_text), choice == 1,
            { choice = 1 }, Modifier.fillMaxWidth().testTag("gallery-option-two"))
        OptionCard(stringResource(R.string.gallery_disabled), null, false, {}, Modifier.fillMaxWidth(), enabled = false)
    }
    SegmentedTabs(listOf(SegmentOption(stringResource(R.string.gallery_upcoming), 12), SegmentOption(stringResource(R.string.gallery_past), 0)), tab, { tab = it })
    SegmentedTabs(emptyList(), 0, {})
}

@Composable
private fun DataExamples() {
    val statuses = listOf(StatusTone.Neutral to R.string.status_unknown, StatusTone.Success to R.string.status_active,
        StatusTone.Warning to R.string.status_used, StatusTone.Danger to R.string.status_cancelled)
    statuses.forEach { (tone, label) ->
        StatusBadge(stringResource(label), tone)
        StatusDot(stringResource(label), tone)
        if (tone != StatusTone.Neutral) ResultMark(stringResource(label), tone)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(EventPassDimens.large)) {
        AvatarInitials(stringResource(R.string.gallery_person))
        AvatarInitials("")
        AvatarInitials(stringResource(R.string.gallery_unicode_name))
    }
    MetricCard(stringResource(R.string.gallery_metric), "242", stringResource(R.string.gallery_metric_help), Modifier.fillMaxWidth())
    EventPassSection(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.gallery_card_example), style = EventPassTypography.cardTitle)
        StatusBadge(stringResource(R.string.status_active), StatusTone.Success)
        KeyValueRow(stringResource(R.string.gallery_name), stringResource(R.string.gallery_person))
        KeyValueRow(stringResource(R.string.gallery_email), stringResource(R.string.gallery_long_email))
        Text(stringResource(R.string.gallery_long_text), style = EventPassTypography.body)
    }
}

@Composable
private fun StateExamples() {
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    EmptyState(stringResource(R.string.gallery_empty_title), stringResource(R.string.gallery_empty_body),
        actionLabel = stringResource(R.string.gallery_action), onAction = { count++ })
    ErrorState(stringResource(R.string.gallery_error_title), stringResource(R.string.gallery_error_body), { count++ })
    InlineError(stringResource(R.string.gallery_long_error))
    EventPassButton(stringResource(R.string.gallery_confirm), { dialog = 1 })
    EventPassButton(stringResource(R.string.gallery_destructive), { dialog = 2 }, style = ActionStyle.Danger)
    Text(stringResource(R.string.gallery_click_count, count))
    if (dialog != 0) ConfirmDialog(stringResource(R.string.gallery_dialog_title), stringResource(R.string.gallery_dialog_body),
        stringResource(if (dialog == 2) R.string.gallery_destructive else R.string.gallery_confirm),
        { count++; dialog = 0 }, { dialog = 0 }, destructive = dialog == 2)
}

@Composable
private fun NavigationExamples() {
    var selected by rememberSaveable { mutableStateOf(BottomDestination.Tickets) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    Text(stringResource(R.string.gallery_navigation_hint), style = EventPassTypography.hint)
    EventPassTopBar(variant = TopBarVariant.Guest, onSignIn = { count++ }, insets = WindowInsets(0))
    EventPassTopBar(variant = TopBarVariant.Authenticated, onProfile = { count++ }, insets = WindowInsets(0))
    EventPassTopBar(stringResource(R.string.gallery_nested_title), variant = TopBarVariant.Nested, onBack = { count++ }, insets = WindowInsets(0))
    EventPassBottomBar(selected, { selected = it }, insets = WindowInsets(0))
    Text(stringResource(R.string.gallery_click_count, count))
}
