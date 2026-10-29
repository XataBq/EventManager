package ru.eventpass.app.feature.tickets

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.platform.TicketQrEncoder

@Composable
fun TicketQr(payload: String, number: String) {
    var failed by remember(payload) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, payload) {
        try { value = withContext(Dispatchers.Default) {
            val qr = TicketQrEncoder.encode(payload)
            Bitmap.createBitmap(qr.pixels, qr.size, qr.size, Bitmap.Config.ARGB_8888)
        } } catch (_: IllegalArgumentException) { failed = true }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 384.dp).fillMaxWidth().aspectRatio(1f), color = Color.White, shape = EventPassShapes.section) {
            bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.ticket_qr_accessibility, number), Modifier.fillMaxSize().testTag("ticket-qr")) }
                ?: Box(contentAlignment = Alignment.Center) { Text(stringResource(if (failed) R.string.ticket_qr_error else R.string.loading), color = Color.Black) }
        }
    }
}

@Composable
fun FullscreenTicketQr(payload: String, number: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val previous = window?.attributes?.screenBrightness
            window?.attributes = window?.attributes?.also { it.screenBrightness = 1f }
            onDispose { if (previous != null) window?.attributes = window?.attributes?.also { it.screenBrightness = previous } }
        }
        Surface(Modifier.fillMaxSize(), color = EventPassColors.background) {
            Box(Modifier.safeDrawingPadding()) {
                AuthColumn {
                    TextAction(stringResource(R.string.ticket_back), onClose, Modifier.testTag("close-fullscreen"))
                    Text(stringResource(R.string.ticket_number, number), style = EventPassTypography.heading)
                    TicketQr(payload, number)
                }
            }
        }
    }
}
