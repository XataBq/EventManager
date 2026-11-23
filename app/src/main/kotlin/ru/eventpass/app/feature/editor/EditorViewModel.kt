package ru.eventpass.app.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.eventpass.core.domain.EventRepository
import ru.eventpass.core.domain.event.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class EditorInput(val fields: EventFields = EventFields(), val start: String = "", val end: String = "",
    val unlimited: Boolean = false, val capacity: String = "") {
    fun resolved(): Pair<EventFields, Set<String>> {
        val errors = mutableSetOf<String>()
        val zone = runCatching { ZoneId.of(fields.zoneId) }.getOrNull()
        if (zone == null) errors += "zoneId"
        fun date(text: String, key: String): java.time.Instant? {
            if (text.isBlank()) return null
            val result = runCatching {
                val local = LocalDateTime.parse(text.trim(), formatter)
                val offsets = requireNotNull(zone).rules.getValidOffsets(local)
                require(offsets.size == 1)
                local.toInstant(offsets.single())
            }.getOrNull()
            if (result == null) errors += key
            return result
        }
        val start = date(start, "startsAt"); val end = date(end, "endsAt")
        val limit = capacity.toIntOrNull()?.takeIf { it > 0 }
        if (capacity.isNotBlank() && limit == null) errors += "capacity"
        return fields.copy(startsAt = start, endsAt = end, capacity = limit) to errors
    }
    companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
        fun from(fields: EventFields): EditorInput {
            fun display(value: java.time.Instant?) = value?.let { time ->
                runCatching { time.atZone(ZoneId.of(fields.zoneId)).format(formatter) }.getOrElse { time.toString() }
            }.orEmpty()
            return EditorInput(fields, display(fields.startsAt), display(fields.endsAt), false, fields.capacity?.toString().orEmpty())
        }
    }
}
data class EditorState(val loading: Boolean = true, val allowed: Boolean = false, val input: EditorInput = EditorInput(),
    val busy: Boolean = false, val dirty: Boolean = false, val error: EventEditError? = null, val questionsLocked: Boolean = false,
    val terminal: Boolean = false, val saved: Boolean = false, val publishedId: String? = null,
    val confirmExit: Boolean = false, val exit: Boolean = false, val coverError: CoverImportResult? = null,
    val publicSlug: String? = null, val pendingCreate: Boolean = false, val refreshing: Boolean = false,
    val recoveryRequired: Boolean = false, val local: LocalDraftState? = null, val syncing: Boolean = false,
    val merging: Boolean = false, val mergeConflicts: Set<String> = emptySet())

@HiltViewModel
class EditorViewModel @Inject constructor(private val events: EventRepository, private val covers: CoverImporter,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(EditorState()); val state = mutable.asStateFlow()
    private var observation: Job? = null; private var operation: Job? = null
    private var read: Job? = null
    private var sync: Job? = null; private var syncGeneration=0L
    private var observedFields: EventFields? = null
    private var identity: String? = null; private var generation = 0L
    private var draftId: DraftId? = null; private var event: Event? = null; private var revision: Long? = null
    private var target: EventEditorTarget? = null
    private var creating = false
    private var pendingFields: EventFields? = null
    private var recoveryBlocked = false
    private var readGeneration = 0L
    private var publishedTarget = false
    fun bind(draft: String?, published: String?, create: Boolean, remoteDraft: String? = null) {
        val key = "$draft/$published/$create/$remoteDraft"; if (identity == key) return
        val previousDraft = savedState.get<String>("editorLocalDraft")
        if((identity != null && identity != key) || (previousDraft != null && previousDraft != draft)) {
            pendingFields=null; recoveryBlocked=false
            savedState.remove<String>("editorResolvedLocal"); savedState.remove<String>("editorServerDraft"); savedState.remove<Boolean>("editorCreatePending")
        }
        savedState["editorLocalDraft"] = draft
        leave(); identity = key; draftId = (savedState.get<String>("editorResolvedLocal") ?: savedState.get<String>("editorServerDraft") ?: draft)?.let(::DraftId); mutable.value = EditorState()
        if (listOfNotNull(draft,published,remoteDraft).size != 1 || !EventEditingPolicy.validId(draft ?: published ?: remoteDraft.orEmpty())) {
            mutable.value = EditorState(loading = false, error = EventEditError.NotFound); return
        }
        val target = if (published != null) EventEditorTarget.Published(EventId(published)) else if(draftId != null) EventEditorTarget.Draft(draftId!!) else EventEditorTarget.RemoteDraft(EventId(remoteDraft!!))
        this.target = target; creating = create && savedState.get<String>("editorServerDraft") == null; publishedTarget = published != null
        if (creating) events.beginDraft(draftId!!)
        if (savedState.get<Boolean>("editorCreatePending") == true && pendingFields == null) {
            recoveryBlocked = true
            mutable.value = EditorState(loading = false, error = EventEditError.Conflict,recoveryRequired=true); return
        }
        resumeReading()
    }
    fun resumeReading() {
        val target = target ?: return
        if(recoveryBlocked || observation?.isActive == true) return
        val token = ++readGeneration
        observation = viewModelScope.launch { events.observeEditor(target).collect { result ->
            if(token != readGeneration) return@collect
            val old = mutable.value
            when (result) {
                is EventEditorResult.Draft -> {
                    val local=result.local
                    if(local!=null) {
                        draftId=result.draft.id; savedState["editorResolvedLocal"]=result.draft.id.value
                        this@EditorViewModel.target=EventEditorTarget.Draft(result.draft.id)
                        observedFields=result.draft.fields
                    }
                    if (!old.dirty && !old.busy && !old.merging) { revision = result.draft.revision; mutable.value = old.copy(input = EditorInput.from(result.draft.fields)) }
                    mutable.value = mutable.value.copy(loading = false, allowed = true, error = old.error?.takeUnless(::transient),local=local,
                        terminal=local?.terminal ?: old.terminal, publicSlug=result.draft.slug?.value ?: old.publicSlug,
                        publishedId=if(local?.state==DraftSyncState.PUBLISHED && local.conflict==null) result.draft.publishedEventId?.value else old.publishedId)
                }
                is EventEditorResult.Published -> {
                    if (!old.dirty && !old.busy) { event = result.event; revision = result.event.revision; mutable.value = old.copy(input = EditorInput.from(result.event.toFields())) }
                    mutable.value = mutable.value.copy(loading = false, allowed = true, error = old.error?.takeUnless(::transient), questionsLocked = result.questionsLocked,
                        terminal = result.terminal,
                        publicSlug = result.event.slug?.value,
                        publishedId = if (!publishedTarget) result.event.id.value else old.publishedId)
                }
                is EventEditorResult.Failed -> {
                    if (old.allowed && transient(result.error)) {
                        mutable.value = old.copy(loading = false, error = result.error)
                    } else if (creating && result.error == EventEditError.NotFound && revision == null) {
                        mutable.value = old.copy(loading = false, allowed = true)
                    } else {
                        operation?.cancel(); generation++
                        mutable.value = EditorState(loading = false, error = result.error)
                    }
                }
            }
        } }
    }
    fun pauseReading(cancelOperation: Boolean = true) {
        readGeneration++; observation?.cancel(); read?.cancel()
        mutable.value = mutable.value.copy(refreshing=false)
        if(cancelOperation) {
            generation++; operation?.cancel(); syncGeneration++; sync?.cancel()
            mutable.value = mutable.value.copy(busy=false,syncing=false,pendingCreate=pendingFields != null)
        }
    }
    fun edit(input: EditorInput) {
        val s = mutable.value
        if (!s.allowed || s.busy || s.terminal || s.publishedId != null || s.pendingCreate) return
        var fields = if (s.questionsLocked) input.fields.copy(questions = s.input.fields.questions) else input.fields
        if(s.input.fields.cover != null && fields.cover == null) fields = fields.copy(remoteCoverAssetId = null,remoteCoverUrl = null)
        mutable.value = s.copy(input = input.copy(fields = fields), dirty = true, error = null, saved = false, coverError = null)
    }
    fun refresh() {
        val resource = target ?: return
        if(observation?.isActive != true || read?.isActive == true || state.value.busy) return
        val token=readGeneration
        mutable.value=mutable.value.copy(refreshing=true)
        val job=viewModelScope.launch(start=kotlinx.coroutines.CoroutineStart.LAZY) {
            try { events.refreshEditor(resource) }
            finally { if(token == readGeneration) mutable.value=mutable.value.copy(refreshing=false) }
        }
        read=job;job.start()
    }
    fun requestBack() { if (!mutable.value.busy) mutable.value = mutable.value.copy(confirmExit = mutable.value.dirty, exit = !mutable.value.dirty) }
    fun stay() { mutable.value = mutable.value.copy(confirmExit = false) }
    fun discard() { generation++; operation?.cancel(); mutable.value = mutable.value.copy(exit = true, busy = false, confirmExit = false) }
    fun save(publish: Boolean = false, exit: Boolean = false) {
        val s = mutable.value
        if (!s.allowed || s.busy || s.terminal || s.publishedId != null) return
        if(publish && (s.syncing || s.local?.conflict!=null)) return
        val (inputFields, inputErrors) = s.input.resolved()
        val errors = inputErrors.toMutableSet()
        if(publish && pendingFields == null) errors += EventEditingPolicy.publicationErrors(inputFields, java.time.Instant.now())
        val fields = pendingFields ?: inputFields
        if (errors.isNotEmpty()) { mutable.value = s.copy(error = EventEditError.Validation(errors)); return }
        runOperation {
            val existing = event
            if (existing != null) {
                when (val result = current { events.updateEvent(existing.id, revision, existing.localSchemaRevision, fields) }) {
                    is EventEditResult.Failed -> fail(result.error)
                    is EventEditResult.Saved -> { event = result.value; revision = result.value.revision; accepted(exit) }
                }
            } else {
                if (creating && s.local==null) { pendingFields = fields; savedState["editorCreatePending"] = true }
                when (val saved = current { events.saveDraft(requireNotNull(draftId), revision, fields) }) {
                    is EventEditResult.Failed -> {
                        val unknown = saved.error is EventEditError.RateLimited || saved.error in setOf(EventEditError.Network, EventEditError.Timeout, EventEditError.Protocol, EventEditError.ServerUnavailable)
                        if (!unknown) { pendingFields = null; savedState.remove<Boolean>("editorCreatePending") }
                        mutable.value = mutable.value.copy(pendingCreate = creating && unknown)
                        fail(saved.error)
                    }
                    is EventEditResult.Saved -> {
                        if(s.local==null) savedState["editorServerDraft"] = saved.value.id.value
                        else savedState["editorResolvedLocal"] = saved.value.id.value
                        creating = false; pendingFields = null; savedState.remove<Boolean>("editorCreatePending")
                        mutable.value = mutable.value.copy(pendingCreate = false)
                        revision = saved.value.revision; observedFields=saved.value.fields; accepted(false)
                        if(s.local!=null) mutable.value=mutable.value.copy(local=s.local.copy(state=if(s.local.conflict==null) DraftSyncState.LOCAL else DraftSyncState.CONFLICT))
                        if (publish) when (val result = current { events.publishDraft(requireNotNull(draftId), saved.value.revision) }) {
                            is EventEditResult.Failed -> fail(result.error)
                            is EventEditResult.Saved -> mutable.value = mutable.value.copy(publishedId = result.value.value, saved = false)
                        } else accepted(exit)
                    }
                }
            }
        }
    }
    fun manage(finish: Boolean) {
        if (mutable.value.busy || mutable.value.dirty) return
        val existing = event ?: return
        runOperation {
            if (!finish) return@runOperation
            val result = current { events.finishEvent(existing.id, revision) }
            when (result) {
                is EventEditResult.Failed -> fail(result.error)
                is EventEditResult.Saved -> { event = result.value; revision = result.value.revision; accepted(false) }
            }
        }
    }
    fun importCover(source: String) {
        if (mutable.value.busy || !mutable.value.allowed || mutable.value.terminal || mutable.value.pendingCreate) return
        runOperation {
            when (val result = current { covers.importCover(source) }) {
                is CoverImportResult.Imported -> mutable.value = mutable.value.copy(input = mutable.value.input.let {
                    it.copy(fields = it.fields.copy(cover = result.cover,remoteCoverAssetId = result.cover.remoteAssetId)) }, dirty = true, saved = false, coverError = null)
                else -> mutable.value = mutable.value.copy(coverError = result)
            }
        }
    }
    fun synchronize() {
        val id=draftId ?: return
        if(state.value.local==null || state.value.busy || state.value.dirty || sync?.isActive==true) return
        val token=++syncGeneration
        mutable.value=mutable.value.copy(syncing=true,error=null)
        sync=viewModelScope.launch {
            try { when(val result=events.syncDraft(id)) {
                is EventEditResult.Failed -> if(token==syncGeneration) fail(result.error)
                is EventEditResult.Saved -> Unit // Only the Room observer may declare server acknowledgement.
            } } finally { if(token==syncGeneration) mutable.value=mutable.value.copy(syncing=false) }
        }
    }
    fun beginMerge() {
        val conflict=state.value.local?.conflict ?: return
        val proposal=DraftMergePolicy.merge(conflict.base,conflict.local,conflict.remote)
        mutable.value=mutable.value.copy(input=EditorInput.from(proposal.fields),merging=true,mergeConflicts=proposal.conflicts,dirty=true,error=null)
    }
    fun mergeCollection(remote: Boolean) {
        val conflict=state.value.local?.conflict ?: return
        val source=if(remote) conflict.remote else conflict.local
        edit(state.value.input.copy(fields=state.value.input.fields.copy(questions=source.questions,sections=source.sections)))
    }
    fun resolve(resolution: DraftResolution) {
        val id=draftId ?: return
        if(state.value.busy || state.value.syncing) return
        val (fields,errors)=state.value.input.resolved()
        if(resolution!=DraftResolution.REMOTE && errors.isNotEmpty()) { fail(EventEditError.Validation(errors));return }
        runOperation { when(val result=current {events.resolveDraft(id,resolution,fields)}) {
            is EventEditResult.Failed -> fail(result.error)
            is EventEditResult.Saved -> {
                observedFields=result.value.fields
                mutable.value=mutable.value.copy(input=EditorInput.from(result.value.fields),merging=false,mergeConflicts=emptySet())
                accepted(false)
            }
        } }
    }
    fun discardPersisted() {
        val id=draftId ?: return
        if(state.value.busy || state.value.syncing) return
        runOperation { when(val result=current { events.discardDraft(id) }) {
            is EventEditResult.Failed -> fail(result.error)
            is EventEditResult.Saved -> mutable.value=mutable.value.copy(exit=true,dirty=false,confirmExit=false)
        } }
    }
    private fun accepted(exit: Boolean) { mutable.value = mutable.value.copy(dirty = false, saved = true, error = null, confirmExit = false, exit = exit) }
    private fun fail(error: EventEditError) {
        if(error in setOf(EventEditError.AuthenticationRequired,EventEditError.Forbidden,EventEditError.SessionChanged)) {
            pendingFields=null; savedState.remove<Boolean>("editorCreatePending"); event=null; revision=null
            mutable.value=EditorState(loading=false,error=error)
        } else mutable.value = mutable.value.copy(error = error, confirmExit = false)
    }
    private fun transient(error: EventEditError) = error in setOf(EventEditError.Network, EventEditError.Timeout, EventEditError.StorageUnavailable)
    private suspend fun <T> current(block: suspend () -> T): T {
        val token = generation
        val result = block()
        currentCoroutineContext().ensureActive()
        if (token != generation) throw CancellationException("Editor entry changed")
        return result
    }
    private fun runOperation(block: suspend () -> Unit) {
        val token = ++generation
        mutable.value = mutable.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { block() } finally { if (token == generation) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun leave() { generation++; readGeneration++; observation?.cancel(); read?.cancel(); operation?.cancel(); syncGeneration++; sync?.cancel(); observedFields=null; identity = null; revision = null; event = null }
}
