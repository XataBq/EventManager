package ru.eventpass.core.designsystem

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

enum class EventPassGlyph(val resource: Int) {
    Ticket(R.drawable.ic_ticket), Calendar(R.drawable.ic_calendar), User(R.drawable.ic_user),
    Back(R.drawable.ic_back), Brand(R.drawable.ic_brand), Avatar(R.drawable.ic_avatar_user),
    Search(R.drawable.ic_search), Visible(R.drawable.ic_visibility), Hidden(R.drawable.ic_visibility_off),
    Close(R.drawable.ic_close), Check(R.drawable.ic_check), Error(R.drawable.ic_error_outline),
    TicketAction(R.drawable.ic_ticket_action), Download(R.drawable.ic_download),
}

/** Intrinsic vector dimensions preserve the Figma glyph geometry inside larger touch targets. */
@Composable
fun EventPassIcon(
    glyph: EventPassGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = EventPassColors.textSecondary,
) {
    Icon(painterResource(glyph.resource), contentDescription, modifier, tint)
}
