package ru.eventpass.app.feature.myevents

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.feature.editor.editErrorResource
import ru.eventpass.app.feature.event.formatEventTime
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*

data class MyEventsUiState(val content: MyEventsResult? = null, val error: EventEditError? = null,
    val loading: Boolean = true, val refreshing: Boolean = false)
@HiltViewModel
class MyEventsViewModel @Inject constructor(private val events: EventRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(MyEventsUiState()); val data = mutable.asStateFlow()
    private var active = false; private var owner = 0L
    private var observer: Job? = null; private var read: Job? = null
    fun start() {
        if (active) return
        active = true; val generation = owner
        mutable.value = data.value.copy(loading = data.value.content == null)
        observer = viewModelScope.launch { events.observeMyEvents().collect { next -> if (generation == owner) {
            val old = data.value
            mutable.value = if (next is MyEventsResult.Failed && next.error in setOf(EventEditError.Network, EventEditError.Timeout, EventEditError.StorageUnavailable)
                && old.content is MyEventsResult.Ready) old.copy(error = next.error, loading = false)
            else old.copy(content = next, error = null, loading = false)
        } } }
    }
    fun stop() { active = false; owner++; observer?.cancel(); read?.cancel()
        mutable.value = data.value.copy(loading = false, refreshing = false) }
    val tab = saved.getStateFlow("myEventsTab", 0)
    fun select(value: Int) { saved["myEventsTab"] = value.coerceIn(0, 2) }
    fun refresh() {
        if (!active || read?.isActive == true) return
        val generation = owner; mutable.value = data.value.copy(refreshing = !data.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { events.refreshMyEvents() }
            finally { if (generation == owner) mutable.value = data.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
}
@Composable
fun MyEventsRoute(onCreate: () -> Unit, onDraft: (String) -> Unit, onEdit: (String) -> Unit, onOpen: (String) -> Unit,
    onScan: (String) -> Unit, onVolunteers: (String) -> Unit, onDashboard: (String) -> Unit, visible: Boolean = true,
    vm: MyEventsViewModel = hiltViewModel()) {
    val data by vm.data.collectAsStateWithLifecycle(); val tab by vm.tab.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(vm, lifecycle, visible) { if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(); vm.refresh(); try { awaitCancellation() } finally { vm.stop() }
    } }
    MyEventsScreen(data.content, tab, vm::select, vm::refresh, onCreate, onDraft, onEdit, onOpen, { id ->
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("EventPass", "eventpass-demo://demo/e/$id")); copied = true
    }, copied, data.error, onScan, onVolunteers, onDashboard, data.refreshing, visible && !data.loading)
}
@Composable
fun MyEventsScreen(data: MyEventsResult?, tab: Int, select: (Int) -> Unit, refresh: () -> Unit,
    create: () -> Unit, draft: (String) -> Unit, edit: (String) -> Unit, open: (String) -> Unit, copyLink: (String) -> Unit, copied: Boolean, readError: EventEditError?,
    scan: (String) -> Unit, volunteers: (String) -> Unit, dashboard: (String) -> Unit,
    refreshing: Boolean = false, refreshEnabled: Boolean = true) {
    val denied = (data as? MyEventsResult.Failed)?.error in setOf(EventEditError.AuthenticationRequired, EventEditError.SessionChanged, EventEditError.Forbidden)
    EventPassRefresh(refreshing, refresh, Modifier.testTag("refresh-events"), enabled = refreshEnabled && !denied) {
    AuthColumn(Modifier.testTag("root-Events")) {
        Text(stringResource(R.string.events_title), style = EventPassTypography.heading)
        EventPassButton(stringResource(R.string.editor_new), create, Modifier.testTag("create-event"))
        SegmentedTabs(listOf(SegmentOption(stringResource(R.string.my_organizing)), SegmentOption(stringResource(R.string.my_volunteering)),
            SegmentOption(stringResource(R.string.my_drafts))), tab, select)
        if (copied) Text(stringResource(R.string.editor_copied))
        readError?.let { InlineError(stringResource(editErrorResource(it))); TextAction(stringResource(R.string.ticket_retry), refresh) }
        when (data) {
            null -> Text(stringResource(R.string.loading))
            is MyEventsResult.Failed -> {
                InlineError(stringResource(editErrorResource(data.error)))
                TextAction(stringResource(R.string.ticket_retry), refresh)
            }
            is MyEventsResult.Ready -> {
                if (tab == 2) {
                    if (data.drafts.isEmpty()) Text(stringResource(R.string.my_empty))
                    data.drafts.forEach { d -> EventPassSection {
                        Text(d.fields.title.ifBlank { stringResource(R.string.editor_untitled) }, style = EventPassTypography.emphasis)
                        TextAction(stringResource(R.string.editor_continue), { draft(d.id.value) }, Modifier.testTag("draft-${d.id.value}"))
                    } }
                } else {
                    val items = if (tab == 0) data.organizing else data.volunteering
                    if (items.isEmpty()) Text(stringResource(R.string.my_empty))
                    items.forEach { e -> EventPassSection {
                        Text(e.title, style = EventPassTypography.emphasis)
                        Text(formatEventTime(e.startsAt, e.zoneId)); Text(e.venue)
                        val finished = e.lifecycle == EventLifecycle.FINISHED || e.endsAt?.let { data.now >= it } == true
                        StatusBadge(stringResource(when {
                            e.lifecycle == EventLifecycle.CANCELLED -> R.string.event_cancelled
                            finished -> R.string.event_finished
                            data.now >= e.startsAt -> R.string.event_started
                            !e.registrationOpen -> R.string.event_closed
                            else -> R.string.event_open
                        }), StatusTone.Neutral)
                        TextAction(stringResource(R.string.editor_open), { open(e.id.value) })
                        if (tab == 0) TextAction(stringResource(R.string.dashboard_title), { dashboard(e.id.value) }, Modifier.testTag("dashboard-${e.id.value}"))
                        TextAction(stringResource(R.string.scan_title), { scan(e.id.value) }, Modifier.testTag("scan-${e.id.value}"))
                        if (tab == 0) TextAction(stringResource(R.string.volunteers_title), { volunteers(e.id.value) }, Modifier.testTag("volunteers-${e.id.value}"))
                        if (tab == 0) TextAction(stringResource(R.string.editor_edit), { edit(e.id.value) }, Modifier.testTag("edit-${e.id.value}"))
                        if (tab == 0) TextAction(stringResource(R.string.editor_copy), { copyLink(e.id.value) })
                    } }
                }
            }
        }
    }
    }
}
