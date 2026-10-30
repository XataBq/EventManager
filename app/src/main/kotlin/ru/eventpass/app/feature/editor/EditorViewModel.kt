package ru.eventpass.app.feature.editor

import androidx.lifecycle.ViewModel
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
    val unlimited: Boolean = true, val capacity: String = "") {
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
        val limit = if (unlimited) null else capacity.toIntOrNull()?.takeIf { it >= 0 }
        if (!unlimited && limit == null) errors += "capacity"
        return fields.copy(startsAt = start, endsAt = end, capacity = limit) to errors
    }
    companion object {
        val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
        fun from(fields: EventFields): EditorInput {
            fun display(value: java.time.Instant?) = value?.let { time ->
                runCatching { time.atZone(ZoneId.of(fields.zoneId)).format(formatter) }.getOrElse { time.toString() }
            }.orEmpty()
            return EditorInput(fields, display(fields.startsAt), display(fields.endsAt), fields.capacity == null, fields.capacity?.toString().orEmpty())
        }
    }
}
data class EditorState(val loading: Boolean = true, val allowed: Boolean = false, val input: EditorInput = EditorInput(),
    val busy: Boolean = false, val dirty: Boolean = false, val error: EventEditError? = null, val questionsLocked: Boolean = false,
    val terminal: Boolean = false, val saved: Boolean = false, val publishedId: String? = null,
    val confirmExit: Boolean = false, val exit: Boolean = false, val coverError: CoverImportResult? = null)

@HiltViewModel
class EditorViewModel @Inject constructor(private val events: EventRepository, private val covers: CoverImporter) : ViewModel() {
    private val mutable = MutableStateFlow(EditorState()); val state = mutable.asStateFlow()
    private var observation: Job? = null; private var operation: Job? = null
    private var identity: String? = null; private var generation = 0L
    private var draftId: DraftId? = null; private var event: Event? = null; private var revision: Long? = null
    fun bind(draft: String?, published: String?, create: Boolean) {
        val key = "$draft/$published/$create"; if (identity == key) return
        leave(); identity = key; draftId = draft?.let(::DraftId); mutable.value = EditorState()
        if ((draft == null) == (published == null) || !EventEditingPolicy.validId(draft ?: published.orEmpty())) {
            mutable.value = EditorState(loading = false, error = EventEditError.NotFound); return
        }
        val target = if (published != null) EventEditorTarget.Published(EventId(published)) else EventEditorTarget.Draft(draftId!!)
        observation = viewModelScope.launch { events.observeEditor(target).collect { result ->
            val old = mutable.value
            when (result) {
                is EventEditorResult.Draft -> {
                    if (!old.dirty && !old.busy) { revision = result.draft.revision; mutable.value = old.copy(input = EditorInput.from(result.draft.fields)) }
                    mutable.value = mutable.value.copy(loading = false, allowed = true, error = old.error?.takeUnless(::transient))
                }
                is EventEditorResult.Published -> {
                    if (!old.dirty && !old.busy) { event = result.event; revision = result.event.revision; mutable.value = old.copy(input = EditorInput.from(result.event.toFields())) }
                    mutable.value = mutable.value.copy(loading = false, allowed = true, error = old.error?.takeUnless(::transient), questionsLocked = result.questionsLocked,
                        terminal = EventEditingPolicy.updateError(result.event, result.event.toFields(), result.now, 0, false) == EventEditError.EventFinished,
                        publishedId = if (published == null) result.event.id.value else old.publishedId)
                }
                is EventEditorResult.Failed -> {
                    if (old.allowed && transient(result.error)) {
                        mutable.value = old.copy(loading = false, error = result.error)
                    } else if (create && result.error == EventEditError.NotFound && revision == null) {
                        mutable.value = old.copy(loading = false, allowed = true)
                    } else {
                        operation?.cancel(); generation++
                        mutable.value = EditorState(loading = false, error = result.error)
                    }
                }
            }
        } }
    }
    fun edit(input: EditorInput) {
        val s = mutable.value
        if (!s.allowed || s.busy || s.terminal || s.publishedId != null) return
        val fields = if (s.questionsLocked) input.fields.copy(questions = s.input.fields.questions) else input.fields
        mutable.value = s.copy(input = input.copy(fields = fields), dirty = true, error = null, saved = false, coverError = null)
    }
    fun refresh() { viewModelScope.launch { events.refreshMyEvents() } }
    fun requestBack() { if (!mutable.value.busy) mutable.value = mutable.value.copy(confirmExit = mutable.value.dirty, exit = !mutable.value.dirty) }
    fun stay() { mutable.value = mutable.value.copy(confirmExit = false) }
    fun discard() { generation++; operation?.cancel(); mutable.value = mutable.value.copy(exit = true, busy = false, confirmExit = false) }
    fun save(publish: Boolean = false, exit: Boolean = false) {
        val s = mutable.value
        if (!s.allowed || s.busy || s.terminal || s.publishedId != null) return
        val (fields, errors) = s.input.resolved()
        if (errors.isNotEmpty()) { mutable.value = s.copy(error = EventEditError.Validation(errors)); return }
        runOperation {
            val existing = event
            if (existing != null) {
                when (val result = current { events.updateEvent(existing.id, requireNotNull(revision), existing.formVersion, fields) }) {
                    is EventEditResult.Failed -> fail(result.error)
                    is EventEditResult.Saved -> { event = result.value; revision = result.value.revision; accepted(exit) }
                }
            } else {
                when (val saved = current { events.saveDraft(requireNotNull(draftId), revision, fields) }) {
                    is EventEditResult.Failed -> fail(saved.error)
                    is EventEditResult.Saved -> {
                        revision = saved.value.revision; accepted(false)
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
            val result = current { if (finish) events.finishEvent(existing.id, requireNotNull(revision)) else events.closeRegistration(existing.id, requireNotNull(revision)) }
            when (result) {
                is EventEditResult.Failed -> fail(result.error)
                is EventEditResult.Saved -> { event = result.value; revision = result.value.revision; accepted(false) }
            }
        }
    }
    fun importCover(source: String) {
        if (mutable.value.busy || !mutable.value.allowed || mutable.value.terminal) return
        runOperation {
            when (val result = current { covers.importCover(source) }) {
                is CoverImportResult.Imported -> mutable.value = mutable.value.copy(input = mutable.value.input.let {
                    it.copy(fields = it.fields.copy(cover = result.cover)) }, dirty = true, saved = false, coverError = null)
                else -> mutable.value = mutable.value.copy(coverError = result)
            }
        }
    }
    private fun accepted(exit: Boolean) { mutable.value = mutable.value.copy(dirty = false, saved = true, error = null, confirmExit = false, exit = exit) }
    private fun fail(error: EventEditError) { mutable.value = mutable.value.copy(error = error, confirmExit = false) }
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
    fun leave() { generation++; observation?.cancel(); operation?.cancel(); identity = null; revision = null; event = null }
}
