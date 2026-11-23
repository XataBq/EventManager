package ru.eventpass.app.feature.myevents

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
import ru.eventpass.app.navigation.ReportInitialContent
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
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
    private val tabs = mutableMapOf<Int, MyEventsResult.Ready>()
    fun start() {
        if (active) return
        active = true; val generation = owner
        mutable.value = data.value.copy(loading = data.value.content == null)
        val selected = tab.value
        observer = viewModelScope.launch { events.observeMyEvents(MyEventsTab.entries[selected]).collect { next -> if (generation == owner) {
            val old = data.value
            if(next is MyEventsResult.Ready) tabs[selected] = next
            if(next is MyEventsResult.Failed && next.error in setOf(EventEditError.AuthenticationRequired,EventEditError.Forbidden,EventEditError.SessionChanged)) tabs.clear()
            mutable.value = if (next is MyEventsResult.Failed && next.error in setOf(EventEditError.Network, EventEditError.Timeout, EventEditError.StorageUnavailable)
                && old.content is MyEventsResult.Ready) old.copy(content=old.content.copy(localDrafts=next.localDrafts),error = next.error, loading = false)
            else old.copy(content = next, error = null, loading = false)
        } } }
    }
    fun stop() { active = false; owner++; observer?.cancel(); read?.cancel()
        mutable.value = data.value.copy(loading = false, refreshing = false) }
    val tab = saved.getStateFlow("myEventsTab", 0)
    fun select(value: Int) {
        val next = value.coerceIn(0,2)
        if(next == tab.value) return
        val aggregate = (data.value.content as? MyEventsResult.Ready)?.tab == null && data.value.content is MyEventsResult.Ready
        val restart = active && !aggregate
        if(restart) stop()
        saved["myEventsTab"] = next
        if(!aggregate) mutable.value = MyEventsUiState(content=tabs[next],loading=tabs[next] == null)
        if(restart) start()
    }
    fun refresh() {
        if (!active || read?.isActive == true) return
        val generation = owner; mutable.value = data.value.copy(refreshing = !data.value.loading)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { events.refreshMyEvents(MyEventsTab.entries[tab.value]) }
            finally { if (generation == owner) mutable.value = data.value.copy(loading = false, refreshing = false) }
        }
        read = job; job.start()
    }
}
@Composable
fun MyEventsRoute(onCreate: () -> Unit, onDraft: (String) -> Unit, onEdit: (String) -> Unit, onOpen: (String) -> Unit,
    onScan: (String) -> Unit, onVolunteers: (String) -> Unit, onDashboard: (String) -> Unit, visible: Boolean = true,
    vm: MyEventsViewModel = hiltViewModel(), onRemoteDraft: (String) -> Unit = onDraft, showCreate: Boolean = true) {
    val data by vm.data.collectAsStateWithLifecycle(); val tab by vm.tab.collectAsStateWithLifecycle()
    ReportInitialContent(visible && (data.content != null || data.error != null))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(vm, lifecycle, visible) { if (visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(); vm.refresh(); try { awaitCancellation() } finally { vm.stop() }
    } }
    MyEventsScreen(data.content, tab, vm::select, vm::refresh, onCreate, onDraft, onEdit, onOpen, { id ->
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("EventPass", if(ru.eventpass.app.BuildConfig.FLAVOR == "remote") "https://eventpass.dikanskii.ru/e/$id" else "eventpass-demo://demo/e/$id")); copied = true
        android.widget.Toast.makeText(context.applicationContext,R.string.editor_copied,android.widget.Toast.LENGTH_SHORT).show()
    }, copied, data.error, onScan, onVolunteers, onDashboard, data.refreshing, visible && !data.loading, onRemoteDraft, showCreate)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyEventsScreen(data: MyEventsResult?, tab: Int, select: (Int) -> Unit, refresh: () -> Unit,
    create: () -> Unit, draft: (String) -> Unit, edit: (String) -> Unit, open: (String) -> Unit, copyLink: (String) -> Unit, copied: Boolean, readError: EventEditError?,
    scan: (String) -> Unit, volunteers: (String) -> Unit, dashboard: (String) -> Unit,
    refreshing: Boolean = false, refreshEnabled: Boolean = true, remoteDraft: (String) -> Unit = draft, showCreate: Boolean = true) {
    val denied = (data as? MyEventsResult.Failed)?.error in setOf(EventEditError.AuthenticationRequired, EventEditError.SessionChanged, EventEditError.Forbidden)
    EventPassRefresh(refreshing, refresh, Modifier.testTag("refresh-events"), enabled = refreshEnabled && !denied) {
    AuthColumn(Modifier.testTag("root-Events")) {
        Text(stringResource(R.string.events_title), style = EventPassTypography.heading)
        if(showCreate) EventPassButton(stringResource(R.string.editor_new), create, Modifier.testTag("create-event"))
        val options=listOf(R.string.my_organizing,R.string.my_volunteering,R.string.my_drafts).mapIndexed { index,title ->
            val label=stringResource(title)
            val count=(data as? MyEventsResult.Ready)?.counters?.get(MyEventsTab.entries[index])
            SegmentOption(if(count == null) label else stringResource(R.string.my_events_tab_count,label,count))
        }
        SegmentedTabs(options, tab, select)
        if (copied) Text(stringResource(R.string.editor_copied))
        readError?.let { InlineError(stringResource(editErrorResource(it))); TextAction(stringResource(R.string.ticket_retry), refresh) }
        val locals=when(data) { is MyEventsResult.Ready->data.localDrafts; is MyEventsResult.Failed->data.localDrafts; else->emptyList() }
        if(tab==2 && locals.isNotEmpty()) {
            Text(stringResource(R.string.draft_device_heading),style=EventPassTypography.emphasis)
            locals.forEach { local -> EventPassSection {
                Text(local.title.ifBlank { stringResource(R.string.editor_untitled) })
                Text(stringResource(ru.eventpass.app.feature.editor.draftStateResource(local.state)))
                TextAction(stringResource(R.string.editor_continue),{draft(local.id.value)},Modifier.testTag("local-draft-${local.id.value}"))
            } }
        }
        when (data) {
            null -> LoadingSkeleton(LoadingLayout.Events)
            is MyEventsResult.Failed -> {
                InlineError(stringResource(editErrorResource(data.error)))
                TextAction(stringResource(R.string.ticket_retry), refresh)
            }
            is MyEventsResult.Ready -> {
                if(data.tab != null) {
                    if(data.items.isEmpty() && locals.isEmpty()) Text(stringResource(R.string.my_empty))
                    data.items.filterNot { item -> locals.any { it.remoteId==item.id } }.forEach { item -> EventPassSection {
                        Text(item.title.ifBlank { stringResource(R.string.editor_untitled) },style=EventPassTypography.emphasis)
                        item.startsAt?.let { Text(formatEventTime(it,"Europe/Moscow")) }
                        Text(item.location)
                        if(item.status == EventLifecycle.DRAFT) {
                            TextAction(stringResource(R.string.editor_continue),{remoteDraft(item.id.value)})
                        } else {
                            MyEventActions(buildList {
                                add(MyEventAction(stringResource(R.string.editor_open),{open(item.slug.value)}))
                                if((data.tab==MyEventsTab.ORGANIZING && "organizer" in data.roles) ||
                                    (data.tab==MyEventsTab.VOLUNTEERING && "volunteer" in data.roles)) {
                                    add(MyEventAction(stringResource(R.string.scan_title),{scan(item.id.value)},"scan-${item.id.value}"))
                                }
                                if(data.tab==MyEventsTab.ORGANIZING && "organizer" in data.roles) {
                                    add(MyEventAction(stringResource(R.string.dashboard_title),{dashboard(item.id.value)},"dashboard-${item.id.value}"))
                                    add(MyEventAction(stringResource(R.string.volunteers_title),{volunteers(item.id.value)},"volunteers-${item.id.value}"))
                                    add(MyEventAction(stringResource(R.string.editor_edit),{edit(item.id.value)}))
                                    add(MyEventAction(stringResource(R.string.editor_copy),{copyLink(item.slug.value)}))
                                }
                            })
                        }
                    } }
                } else if (tab == 2) {
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
                        MyEventActions(buildList {
                            add(MyEventAction(stringResource(R.string.editor_open),{open(e.id.value)}))
                            if(tab==0) add(MyEventAction(stringResource(R.string.dashboard_title),{dashboard(e.id.value)},"dashboard-${e.id.value}"))
                            add(MyEventAction(stringResource(R.string.scan_title),{scan(e.id.value)},"scan-${e.id.value}"))
                            if(tab==0) {
                                add(MyEventAction(stringResource(R.string.volunteers_title),{volunteers(e.id.value)},"volunteers-${e.id.value}"))
                                add(MyEventAction(stringResource(R.string.editor_edit),{edit(e.id.value)},"edit-${e.id.value}"))
                                add(MyEventAction(stringResource(R.string.editor_copy),{copyLink(e.id.value)}))
                            }
                        })
                    } }
                }
            }
        }
    }
    }
}

private data class MyEventAction(val text:String,val click:()->Unit,val tag:String?=null)

/** Pack adjacent labels only when both fit on one line; never reorder to fill gaps. */
@Composable private fun MyEventActions(actions:List<MyEventAction>) {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val padding=with(density) {(EventPassDimens.large*2+2.dp).toPx()}
        val gap=with(density) {EventPassDimens.small.toPx()}
        val widths=actions.map {measurer.measure(it.text,style=EventPassTypography.emphasis,maxLines=1,softWrap=false).size.width+padding}
        val lineHeight=with(density) {(measurer.measure("Ag",style=EventPassTypography.emphasis,maxLines=1).size.height+EventPassDimens.small.toPx()*2+(EventPassDimens.touch-EventPassDimens.buttonHeight).toPx()).toDp()}
        val height=maxOf(EventPassDimens.touch,lineHeight)
        val rows=buildList {
            var next=0
            while(next<actions.size) {
                if(next+1<actions.size && widths[next]+widths[next+1]+gap<=constraints.maxWidth) {
                    add(listOf(next,next+1));next+=2
                } else {add(listOf(next));next++}
            }
        }
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
            rows.forEach {indices -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                indices.forEach {index ->
                    val action=actions[index]
                    val size=if(indices.size==2) Modifier.height(height) else Modifier.heightIn(min=height)
                    TextAction(action.text,action.click,Modifier.weight(widths[index]).then(size)
                        .then(action.tag?.let {Modifier.testTag(it)} ?: Modifier))
                }
            }}
        }
    }
}
