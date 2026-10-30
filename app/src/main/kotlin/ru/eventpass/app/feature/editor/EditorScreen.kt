package ru.eventpass.app.feature.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.RegistrationQuestion
import java.util.UUID

fun editErrorResource(error: EventEditError): Int = when (error) {
    is EventEditError.Validation -> R.string.editor_invalid
    EventEditError.Conflict, EventEditError.StaleForm -> R.string.editor_conflict
    EventEditError.QuestionsLocked -> R.string.editor_locked
    EventEditError.EventFinished -> R.string.editor_terminal
    EventEditError.Forbidden -> R.string.editor_forbidden
    EventEditError.NotFound -> R.string.event_not_found
    EventEditError.AuthenticationRequired -> R.string.auth_login
    EventEditError.SessionChanged -> R.string.auth_session_changed
    EventEditError.Network -> R.string.auth_network
    EventEditError.Timeout -> R.string.auth_timeout
    EventEditError.StorageUnavailable -> R.string.auth_storage
}

@Composable
fun ManagedCover(cover: EventCover?) {
    val vm: CoverPreviewViewModel = hiltViewModel()
    val preview by vm.image.collectAsStateWithLifecycle()
    LaunchedEffect(cover) { vm.bind(cover) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(EventPassShapes.section).background(EventPassColors.surface), contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap != null) Image(bitmap, stringResource(R.string.event_cover), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(stringResource(R.string.editor_cover_hint), color = EventPassColors.muted, style = EventPassTypography.hint)
    }
}

@Composable
fun EditorRoute(draftId: String?, eventId: String?, create: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit,
    registerBack: ((() -> Unit)?) -> Unit) {
    val vm: EditorViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(draftId, eventId, create) { vm.bind(draftId, eventId, create) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refresh() } }
    DisposableEffect(vm) { registerBack(vm::requestBack); onDispose { registerBack(null) } }
    BackHandler { vm.requestBack() }
    LaunchedEffect(s.exit) { if (s.exit) onBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCover(uri.toString()) }
    val context = LocalContext.current
    var shared by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.editor_share)
    val link = s.publishedId?.let { "eventpass-demo://demo/e/$it" }
    if (link != null) PublishedScreen(link, shared, { onOpen(s.publishedId!!) }, {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EventPass", link)); shared = true
    }, {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
        context.startActivity(Intent.createChooser(send, shareTitle))
    }) else EditorScreen(s, eventId != null, vm::edit, { vm.save() }, { vm.save(publish = true) },
        { picker.launch(arrayOf("image/png", "image/jpeg")) }, vm::refresh, vm::manage, { ManagedCover(s.input.fields.cover) })
    if (s.confirmExit) AlertDialog(onDismissRequest = vm::stay,
        title = { Text(stringResource(R.string.editor_unsaved)) },
        text = { Text(stringResource(R.string.editor_unsaved_hint)) },
        confirmButton = { TextAction(stringResource(R.string.editor_save_exit), { vm.save(exit = true) }, enabled = !s.busy) },
        dismissButton = { Column {
            TextAction(stringResource(R.string.editor_discard), vm::discard, enabled = !s.busy)
            TextAction(stringResource(R.string.editor_stay), vm::stay)
        } })
}

@Composable
fun PublishedScreen(link: String, copied: Boolean, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit) {
    AuthColumn(Modifier.testTag("published-screen")) {
        ResultMark(stringResource(R.string.editor_published), StatusTone.Success)
        Text(stringResource(R.string.editor_public_hint))
        EventPassSection { Text(link, Modifier.testTag("published-link")) }
        EventPassButton(stringResource(if (copied) R.string.editor_copied else R.string.editor_copy), onCopy, Modifier.testTag("copy-link"))
        EventPassButton(stringResource(R.string.editor_share), onShare, style = ActionStyle.Secondary)
        EventPassButton(stringResource(R.string.editor_open), onOpen, Modifier.testTag("open-published"))
    }
}

@Composable
fun EditorScreen(s: EditorState, published: Boolean, onEdit: (EditorInput) -> Unit, onSave: () -> Unit, onPublish: () -> Unit,
    onCover: () -> Unit, onRefresh: () -> Unit, onManage: (Boolean) -> Unit, cover: @Composable () -> Unit) {
    AuthColumn(Modifier.semantics { testTagsAsResourceId = true }.testTag("editor-screen")) {
        Text(stringResource(if (published) R.string.editor_edit else R.string.editor_new), style = EventPassTypography.heading)
        if (s.loading) Text(stringResource(R.string.loading))
        s.error?.let { InlineError(stringResource(editErrorResource(it))) }
        if (s.error != null) TextAction(stringResource(R.string.ticket_retry), onRefresh)
        if (!s.allowed) return@AuthColumn
        val input = s.input; val fields = input.fields; val enabled = !s.busy && !s.terminal
        fun change(f: EventFields) = onEdit(input.copy(fields = f))
        cover()
        EventPassButton(stringResource(R.string.editor_cover), onCover, enabled = enabled, style = ActionStyle.Secondary)
        if (fields.cover != null) TextAction(stringResource(R.string.editor_remove_cover), { change(fields.copy(cover = null)) }, enabled = enabled)
        s.coverError?.let { InlineError(stringResource(when (it) {
            CoverImportResult.TooLarge -> R.string.editor_cover_large
            CoverImportResult.InvalidImage -> R.string.editor_cover_invalid
            else -> R.string.editor_cover_unavailable
        })) }
        val invalid = (s.error as? EventEditError.Validation)?.fields.orEmpty()
        val invalidMessage = stringResource(R.string.editor_field_invalid)
        fun error(key: String): String? = if (key in invalid) invalidMessage else null
        EventPassSection {
            Text(stringResource(R.string.editor_basics), style = EventPassTypography.emphasis)
            FormField(fields.title, { change(fields.copy(title = it)) }, stringResource(R.string.editor_title), Modifier.testTag("editor-title"), enabled = enabled, error = error("title"))
            FormField(input.start, { onEdit(input.copy(start = it)) }, stringResource(R.string.editor_start), Modifier.testTag("editor-start"), placeholder = "2026-10-28 12:00", enabled = enabled, error = error("startsAt"))
            FormField(input.end, { onEdit(input.copy(end = it)) }, stringResource(R.string.editor_end), Modifier.testTag("editor-end"), enabled = enabled, error = error("endsAt"))
            FormField(fields.zoneId, { change(fields.copy(zoneId = it)) }, stringResource(R.string.editor_zone), enabled = enabled, error = error("zoneId"))
            FormField(fields.venue, { change(fields.copy(venue = it)) }, stringResource(R.string.editor_venue), Modifier.testTag("editor-venue"), enabled = enabled, error = error("venue"))
            SettingsSwitch(input.unlimited, { onEdit(input.copy(unlimited = it)) }, stringResource(R.string.editor_unlimited), enabled = enabled)
            if (!input.unlimited) FormField(input.capacity, { onEdit(input.copy(capacity = it)) }, stringResource(R.string.editor_capacity), enabled = enabled, error = error("capacity"))
            if ("datesLocked" in invalid) InlineError(stringResource(R.string.editor_dates_locked))
        }
        EventPassSection {
            Text(stringResource(R.string.editor_questions), style = EventPassTypography.emphasis)
            Text(stringResource(R.string.editor_contact_hint), style = EventPassTypography.hint, color = EventPassColors.muted)
            if (s.questionsLocked) Text(stringResource(R.string.editor_locked), color = EventPassColors.warning)
            error("questions")?.let { InlineError(it) }
            fields.questions.forEachIndexed { index, q -> key(q.id) {
                QuestionEditor(q, enabled && !s.questionsLocked, { changed -> change(fields.copy(questions = fields.questions.map { if (it.id == q.id) changed else it })) },
                    { change(fields.copy(questions = fields.questions.filterNot { it.id == q.id })) },
                    { direction -> val list = fields.questions.toMutableList(); val at = index + direction
                        if (at in list.indices) { java.util.Collections.swap(list, index, at); change(fields.copy(questions = list)) } })
            } }
            if (!s.questionsLocked) QuestionKind.entries.forEach { kind ->
                TextAction(stringResource(R.string.editor_add_question, stringResource(questionKindResource(kind))), {
                    change(fields.copy(questions = fields.questions + DraftQuestion(UUID.randomUUID().toString(), kind = kind)))
                }, enabled = enabled)
            }
        }
        Text(stringResource(R.string.editor_public_hint), style = EventPassTypography.hint)
        if (s.saved) Text(stringResource(R.string.editor_saved), Modifier.testTag("editor-saved"), color = EventPassColors.success)
        if (s.terminal) Text(stringResource(R.string.editor_terminal))
        EventPassButton(stringResource(if (published) R.string.editor_save_changes else R.string.editor_save), onSave, Modifier.testTag("save-draft"), enabled = enabled, loading = s.busy, style = ActionStyle.Secondary)
        if (!published) EventPassButton(stringResource(R.string.editor_publish), onPublish, Modifier.testTag("publish-event"), enabled = enabled, loading = s.busy)
        if (published && !s.terminal) {
            Text(stringResource(R.string.editor_manage_hint), style = EventPassTypography.hint)
            EventPassButton(stringResource(R.string.editor_close), { onManage(false) }, Modifier.testTag("close-registration"), enabled = enabled && !s.dirty, style = ActionStyle.Secondary)
            EventPassButton(stringResource(R.string.editor_finish), { onManage(true) }, Modifier.testTag("finish-event"), enabled = enabled && !s.dirty, style = ActionStyle.Danger)
        }
    }
}

fun questionKindResource(kind: QuestionKind) = when (kind) { QuestionKind.TEXT -> R.string.editor_text; QuestionKind.CHECKBOX -> R.string.editor_checkbox; QuestionKind.SINGLE_CHOICE -> R.string.editor_choice }

@Composable
private fun QuestionEditor(q: DraftQuestion, enabled: Boolean, edit: (DraftQuestion) -> Unit, remove: () -> Unit, move: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
        QuestionKind.entries.forEach { kind -> if (kind != q.kind) TextAction(stringResource(questionKindResource(kind)),
            { edit(q.copy(kind = kind, options = emptyList())) }, enabled = enabled) }
        FormField(q.title, { edit(q.copy(title = it)) }, stringResource(questionKindResource(q.kind)), enabled = enabled)
        SettingsSwitch(q.required, { edit(q.copy(required = it)) }, stringResource(R.string.editor_required), enabled = enabled)
        if (q.kind == QuestionKind.SINGLE_CHOICE) {
            q.options.forEachIndexed { index, option -> FormField(option.title, { text -> edit(q.copy(options = q.options.map { if (it.id == option.id) it.copy(title = text) else it })) },
                stringResource(R.string.editor_option), enabled = enabled, trailingContent = {
                    TextAction(stringResource(R.string.editor_remove), { edit(q.copy(options = q.options.filterNot { it.id == option.id })) }, enabled = enabled)
                })
                Row {
                    fun move(direction: Int) { val items = q.options.toMutableList(); val target = index + direction
                        if (target in items.indices) { java.util.Collections.swap(items, index, target); edit(q.copy(options = items)) } }
                    TextAction(stringResource(R.string.editor_option_up), { move(-1) }, enabled = enabled)
                    TextAction(stringResource(R.string.editor_option_down), { move(1) }, enabled = enabled)
                }
            }
            TextAction(stringResource(R.string.editor_add_option), { edit(q.copy(options = q.options + RegistrationQuestion.Option(UUID.randomUUID().toString(), ""))) }, enabled = enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
            TextAction(stringResource(R.string.editor_up), { move(-1) }, enabled = enabled)
            TextAction(stringResource(R.string.editor_down), { move(1) }, enabled = enabled)
            TextAction(stringResource(R.string.editor_remove), remove, enabled = enabled)
        }
    }
}
