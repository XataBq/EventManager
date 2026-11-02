package ru.eventpass.app.feature.documents

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.eventpass.app.R
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.documents.*

@Composable
fun DocumentActions(request: DocumentRequest, visible: Boolean = true, enabled: Boolean = true) {
    val vm: DocumentsViewModel=hiltViewModel(); val s by vm.state.collectAsStateWithLifecycle()
    var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.destination(launchedId,if(result.resultCode==Activity.RESULT_OK) result.data?.data?.toString() else null); launchedId=null
    }
    DisposableEffect(request,visible) { vm.clear(); onDispose { vm.clear() } }
    LaunchedEffect(s.picker) { s.picker?.takeIf { !it.launched }?.let { pending ->
        launchedId=pending.id; vm.launched(pending.id)
        try { picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type=pending.request.mimeType; putExtra(Intent.EXTRA_TITLE,pending.request.fileName)
        }) } catch(_: ActivityNotFoundException) { launchedId=null; vm.pickerFailed(pending.id) }
    } }
    EventPassButton(stringResource(if(request is DocumentRequest.TicketPdf) R.string.document_pdf else R.string.document_xlsx),
        {vm.begin(request)},Modifier.testTag("document-export"),loading=s.busy,enabled=visible && enabled)
    if(request is DocumentRequest.TicketPdf) EventPassButton(stringResource(R.string.document_resend),{vm.resend(request.ticketId)},
        Modifier.testTag("ticket-resend"),style=ActionStyle.Secondary,enabled=visible && enabled && !s.busy)
    s.error?.let { InlineError(stringResource(documentErrorResource(it))); Text(stringResource(R.string.document_retry_hint)) }
    if(s.saved) Text(stringResource(R.string.document_saved),Modifier.testTag("document-saved"))
    if(s.mailQueued) Text(stringResource(R.string.document_queued),Modifier.testTag("mail-queued"))
    s.retryAt?.let { Text(stringResource(R.string.document_retry_at,it.toString()),Modifier.testTag("mail-retry-at")) }
}
fun documentErrorResource(error: DocumentError): Int=when(error) {
    DocumentError.NETWORK -> R.string.auth_network
    DocumentError.TIMEOUT -> R.string.auth_timeout
    DocumentError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    DocumentError.WRITE_FAILED -> R.string.document_write_failed
    DocumentError.PROVIDER_UNAVAILABLE -> R.string.document_provider_missing
    DocumentError.NOT_CURRENT -> R.string.document_not_current
    else -> R.string.scan_access_lost
}
