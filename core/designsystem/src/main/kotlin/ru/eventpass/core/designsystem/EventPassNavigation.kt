package ru.eventpass.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role

enum class TopBarVariant { Guest, Authenticated, Nested }
enum class BottomDestination { Tickets, Events, Profile }

@Composable
fun EventPassTopBar(
    title: String = stringResource(R.string.ep_brand),
    modifier: Modifier = Modifier,
    variant: TopBarVariant = TopBarVariant.Authenticated,
    subtitle: String? = null,
    onBack: () -> Unit = {},
    onProfile: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    insets: WindowInsets = WindowInsets.statusBars,
) {
    Surface(modifier, color = EventPassColors.background) {
        Row(
            Modifier.windowInsetsPadding(insets).fillMaxWidth().heightIn(min = EventPassDimens.topBar)
                .padding(horizontal = EventPassDimens.large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small),
        ) {
            if (variant == TopBarVariant.Nested) {
                IconButton(onClick = onBack, modifier = Modifier.size(EventPassDimens.touch).testTag("back")) {
                    EventPassIcon(EventPassGlyph.Back, stringResource(R.string.ep_back))
                }
            } else {
                Surface(Modifier.padding(start = EventPassDimens.small), shape = EventPassShapes.control, color = EventPassColors.raised) {
                    Box(Modifier.size(EventPassDimens.avatar), contentAlignment = Alignment.Center) {
                        EventPassIcon(EventPassGlyph.Brand, null, tint = EventPassColors.accentLight)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(vertical = EventPassDimens.small)) {
                Text(title, style = EventPassTypography.cardTitle)
                subtitle?.let { Text(it, style = EventPassTypography.tab, color = EventPassColors.muted) }
            }
            if (variant == TopBarVariant.Guest && onSignIn != null) {
                TextAction(stringResource(R.string.ep_sign_in), onSignIn)
            }
            if (variant == TopBarVariant.Authenticated && onProfile != null) {
                IconButton(onClick = onProfile, modifier = Modifier.size(EventPassDimens.touch)) {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = EventPassColors.accentLight) {
                        Box(Modifier.size(EventPassDimens.avatar), contentAlignment = Alignment.Center) {
                            EventPassIcon(EventPassGlyph.Avatar, stringResource(R.string.ep_profile), tint = EventPassColors.onAccent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventPassBottomBar(
    selected: BottomDestination,
    onSelect: (BottomDestination) -> Unit,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.navigationBars,
) {
    Surface(modifier, color = EventPassColors.background, border = BorderStroke(EventPassDimens.border, EventPassColors.raised)) {
        Row(
            Modifier.windowInsetsPadding(insets).fillMaxWidth().selectableGroup().padding(horizontal = EventPassDimens.small),
        ) {
            BottomDestination.entries.forEach { destination ->
                val (label, icon) = when (destination) {
                    BottomDestination.Tickets -> R.string.ep_tickets to EventPassGlyph.Ticket
                    BottomDestination.Events -> R.string.ep_events to EventPassGlyph.Calendar
                    BottomDestination.Profile -> R.string.ep_profile to EventPassGlyph.User
                }
                val color = if (selected == destination) EventPassColors.accentLight else EventPassColors.textSecondary
                Column(
                    Modifier.weight(1f).heightIn(min = EventPassDimens.bottomBar)
                        .testTag("tab-${destination.name}")
                        .selectable(selected == destination, role = Role.Tab, onClick = { onSelect(destination) })
                        .padding(vertical = EventPassDimens.iconGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(EventPassDimens.tiny),
                ) {
                    Box(Modifier.size(EventPassDimens.icon), contentAlignment = Alignment.Center) { EventPassIcon(icon, null, tint = color) }
                    Text(stringResource(label), style = EventPassTypography.tab, color = color)
                }
            }
        }
    }
}
