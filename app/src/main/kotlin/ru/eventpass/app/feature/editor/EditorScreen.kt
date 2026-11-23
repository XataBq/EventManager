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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.event.*
import ru.eventpass.core.domain.registration.RegistrationQuestion
import java.util.UUID

fun editErrorResource(error: EventEditError): Int = when (error) {
    is EventEditError.RateLimited -> R.string.auth_cooldown
    EventEditError.FeatureUnavailable -> R.string.feature_unavailable
    is EventEditError.Validation -> R.string.editor_invalid
    EventEditError.Conflict, EventEditError.StaleForm -> R.string.editor_conflict
    EventEditError.QuestionsLocked -> R.string.editor_locked
    EventEditError.EventFinished -> R.string.editor_terminal
    EventEditError.Forbidden -> R.string.editor_forbidden
    EventEditError.NotFound -> R.string.event_not_found
    EventEditError.AuthenticationRequired -> R.string.auth_login
    EventEditError.SessionChanged -> R.string.auth_session_changed
    EventEditError.Protocol -> R.string.auth_protocol
    EventEditError.ServerUnavailable -> R.string.m02_server_unavailable
    EventEditError.Network -> R.string.auth_network
    EventEditError.Timeout -> R.string.auth_timeout
    EventEditError.StorageUnavailable -> R.string.auth_storage
}

@Composable
fun ManagedCover(cover: EventCover?, serverId: String? = null) {
    val vm: CoverPreviewViewModel = hiltViewModel()
    val preview by vm.image.collectAsStateWithLifecycle()
    LaunchedEffect(cover,serverId) { vm.bind(cover,serverId) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(EventPassShapes.section).background(EventPassColors.surface), contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap != null) Image(bitmap, stringResource(R.string.event_cover), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(stringResource(R.string.editor_cover_hint), color = EventPassColors.muted, style = EventPassTypography.hint)
    }
}

@Composable
fun EditorRoute(draftId: String?, eventId: String?, create: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit,
    registerBack: ((() -> Unit)?) -> Unit, visible: Boolean = true, remoteDraftId: String? = null) {
    val vm: EditorViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = androidx.activity.compose.LocalActivity.current
    LaunchedEffect(draftId, eventId, create,remoteDraftId) { vm.bind(draftId, eventId, create,remoteDraftId) }
    LaunchedEffect(lifecycle,visible) { if(visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.resumeReading()
        try { kotlinx.coroutines.awaitCancellation() } finally { vm.pauseReading(activity?.isChangingConfigurations != true) }
    } }
    DisposableEffect(vm) { registerBack(vm::requestBack); onDispose { registerBack(null) } }
    BackHandler(enabled=visible) { vm.requestBack() }
    LaunchedEffect(s.exit) { if (s.exit) onBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importCover(uri.toString()) }
    val context = LocalContext.current
    var shared by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.editor_share)
    val link = s.publishedId?.let { if(ru.eventpass.app.BuildConfig.FLAVOR == "remote") s.publicSlug?.let { slug -> "https://eventpass.dikanskii.ru/e/$slug" } else "eventpass-demo://demo/e/$it" }
    if (link != null) PublishedScreen(link, shared, { onOpen(if(ru.eventpass.app.BuildConfig.FLAVOR == "remote") s.publicSlug!! else s.publishedId!!) }, {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EventPass", link)); shared = true
        android.widget.Toast.makeText(context.applicationContext,R.string.editor_copied,android.widget.Toast.LENGTH_SHORT).show()
    }, {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
        context.startActivity(Intent.createChooser(send, shareTitle))
    }) else EditorScreen(s, eventId != null, vm::edit, { vm.save() }, { vm.save(publish = true) },
        { picker.launch(arrayOf("image/png", "image/jpeg")) }, vm::refresh, vm::manage, { ManagedCover(s.input.fields.cover,s.input.fields.remoteCoverAssetId) },refreshEnabled=visible,
        onSync=vm::synchronize,onResolve=vm::resolve,onMerge=vm::beginMerge,onMergeCollection=vm::mergeCollection,onDiscardPersisted=vm::discardPersisted)
    if (s.confirmExit) Dialog(onDismissRequest = { if(!s.busy) vm.stay() },
        properties = DialogProperties(dismissOnBackPress = !s.busy, dismissOnClickOutside = !s.busy)) {
        Surface(shape = EventPassShapes.large, color = EventPassColors.card, contentColor = EventPassColors.textPrimary) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f)
                .verticalScroll(rememberScrollState()).padding(EventPassDimens.page),
                verticalArrangement = Arrangement.spacedBy(EventPassDimens.large)) {
                Text(stringResource(R.string.editor_unsaved), style = EventPassTypography.cardTitle)
                Text(stringResource(R.string.editor_unsaved_hint))
                s.error?.let { InlineError(stringResource(editErrorResource(it))) }
                EventPassButton(stringResource(R.string.editor_save_exit), { vm.save(exit = true) },
                    Modifier.fillMaxWidth(), enabled = !s.busy, loading = s.busy)
                EventPassButton(stringResource(R.string.editor_discard), vm::discard,
                    Modifier.fillMaxWidth(), style = ActionStyle.Danger, enabled = !s.busy)
                EventPassButton(stringResource(R.string.editor_stay), vm::stay,
                    Modifier.fillMaxWidth(), style = ActionStyle.Secondary, enabled = !s.busy)
            }
        }
    }
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
    onCover: () -> Unit, onRefresh: () -> Unit, onManage: (Boolean) -> Unit, cover: @Composable () -> Unit, refreshEnabled: Boolean = true,
    onSync: () -> Unit = {}, onResolve: (DraftResolution) -> Unit = {}, onMerge: () -> Unit = {},
    onMergeCollection: (Boolean) -> Unit = {}, onDiscardPersisted: () -> Unit = {}) {
    CompositionLocalProvider(LocalProminentSecondaryActions provides false) {
    EventPassRefresh(s.refreshing,onRefresh,Modifier.testTag("refresh-editor"),enabled=refreshEnabled && s.allowed && !s.busy && !s.pendingCreate && !s.confirmExit) {
    AuthColumn(Modifier.semantics { testTagsAsResourceId = true }.testTag("editor-screen")) {
        Text(stringResource(if (published) R.string.editor_edit else R.string.editor_new), style = EventPassTypography.heading)
        if (s.loading) LoadingSkeleton(LoadingLayout.Form)
        s.error?.let { InlineError(stringResource(editErrorResource(it))) }
        if(s.recoveryRequired) Text(stringResource(R.string.editor_recovery_required))
        if (s.error != null && s.error !is EventEditError.Validation) TextAction(stringResource(R.string.ticket_retry), onRefresh)
        if (!s.allowed) return@AuthColumn
        s.local?.let { local -> EventPassSection {
            Text(stringResource(draftStateResource(local.state)),style=EventPassTypography.emphasis)
            if(local.problem==DraftProblem.EXPIRED_CREATE) Text(stringResource(R.string.draft_create_expired))
            if(local.problem==DraftProblem.UPLOAD_UNKNOWN) Text(stringResource(R.string.draft_upload_unknown))
            local.retryAt?.let { Text(stringResource(R.string.draft_retry_later)) }
            if(local.state!=DraftSyncState.UNSAVED && local.conflict==null) {
                EventPassButton(stringResource(R.string.draft_sync),onSync,enabled=!s.busy && !s.dirty && !s.syncing,loading=s.syncing)
                Text(stringResource(R.string.draft_sync_hint),style=EventPassTypography.hint)
            }
            local.conflict?.let { conflict ->
                Text(stringResource(R.string.draft_conflict_hint))
                DraftVariant(R.string.draft_base,conflict.base)
                DraftVariant(R.string.draft_local,conflict.local)
                DraftVariant(R.string.draft_remote,conflict.remote)
                TextAction(stringResource(R.string.draft_accept_remote),{onResolve(DraftResolution.REMOTE)},enabled=!s.busy && !s.syncing)
                if(!local.terminal) {
                    TextAction(stringResource(R.string.draft_keep_local),{onResolve(DraftResolution.LOCAL)},enabled=!s.busy && !s.syncing)
                    TextAction(stringResource(R.string.draft_merge),onMerge,enabled=!s.busy && !s.syncing)
                }
                if(s.merging) {
                    Text(stringResource(if(s.mergeConflicts.isEmpty()) R.string.draft_merge_ready else R.string.draft_merge_review))
                    TextAction(stringResource(R.string.draft_collections_local),{onMergeCollection(false)},enabled=!s.busy)
                    TextAction(stringResource(R.string.draft_collections_remote),{onMergeCollection(true)},enabled=!s.busy)
                    EventPassButton(stringResource(R.string.draft_confirm_merge),{onResolve(DraftResolution.MERGED)},enabled=!s.busy && !s.syncing)
                }
            }
            if(local.state!=DraftSyncState.UNSAVED) TextAction(stringResource(R.string.draft_discard_pending),onDiscardPersisted,enabled=!s.busy && !s.syncing)
        } }
        if(s.pendingCreate) Text(stringResource(R.string.editor_pending_create))
        val input = s.input; val fields = input.fields; val enabled = !s.busy && !s.terminal
        fun change(f: EventFields) = onEdit(input.copy(fields = f))
        Box(Modifier.fillMaxWidth().clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,
            onClickLabel=stringResource(R.string.editor_cover),onClick=onCover)) {cover()}
        if (fields.cover != null || fields.remoteCoverAssetId != null) EventPassButton(stringResource(R.string.editor_remove_cover), { change(fields.copy(cover = null,remoteCoverAssetId = null,remoteCoverUrl = null)) }, enabled = enabled,style=ActionStyle.Text,compact=true)
        SegmentedTabs(listOf(SegmentOption(stringResource(R.string.editor_free)),SegmentOption(stringResource(R.string.editor_paid))),if(fields.priceType == PriceType.FREE) 0 else 1,
            { if(enabled) change(fields.copy(priceType=if(it == 0) PriceType.FREE else PriceType.PAID)) })
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
            FormField(fields.title, { change(fields.copy(title = it)) }, stringResource(R.string.editor_title) + " *", Modifier.testTag("editor-title"), enabled = enabled, error = error("title"))
            TemporalField(input.start, { onEdit(input.copy(start = it)) }, stringResource(R.string.editor_start) + " *", Modifier.testTag("editor-start"), enabled = enabled, error = error("startsAt"),zoneId=fields.zoneId)
            TemporalField(input.end, { onEdit(input.copy(end = it)) }, stringResource(R.string.editor_end), Modifier.testTag("editor-end"), enabled = enabled, error = error("endsAt"),zoneId=fields.zoneId,allowClear=true,
                initialValue=runCatching {java.time.LocalDateTime.parse(input.start,EditorInput.formatter).plusHours(4).format(EditorInput.formatter)}.getOrDefault(""))
            FormField(fields.zoneId, { change(fields.copy(zoneId = it)) }, stringResource(R.string.editor_zone), enabled = enabled, error = error("zoneId"))
            FormField(fields.venue, { change(fields.copy(venue = it)) }, stringResource(R.string.editor_venue) + " *", Modifier.testTag("editor-venue"), enabled = enabled, error = error("venue"))
            FormField(fields.description,{change(fields.copy(description=it))},stringResource(R.string.draft_description) + " *",enabled=enabled,error=error("description"))
            FormField(input.capacity, { onEdit(input.copy(capacity = it)) }, stringResource(R.string.editor_capacity) + " *", enabled = enabled, error = error("capacity"))
            if ("datesLocked" in invalid) InlineError(stringResource(R.string.editor_dates_locked))
        }
        EventPassSection {
            Text(stringResource(R.string.editor_questions), style = EventPassTypography.emphasis)
            Text(stringResource(R.string.editor_contact_hint), style = EventPassTypography.hint, color = EventPassColors.muted)
            if (s.questionsLocked) Text(stringResource(R.string.editor_locked), color = EventPassColors.warning)
            error("questions")?.let { InlineError(it) }
            fields.questions.forEachIndexed { index, q -> key(q.id) {
                QuestionEditor(q, index, fields.questions.size, enabled && !s.questionsLocked, { changed -> change(fields.copy(questions = fields.questions.map { if (it.id == q.id) changed else it })) },
                    { change(fields.copy(questions = fields.questions.filterNot { it.id == q.id })) },
                    { direction -> val list = fields.questions.toMutableList(); val at = index + direction
                        if (at in list.indices) { java.util.Collections.swap(list, index, at); change(fields.copy(questions = list)) } }, invalid = "questions" in invalid)
            } }
            if (!s.questionsLocked) {
                if(fields.questions.isEmpty()) Text(stringResource(R.string.editor_questions_empty),
                    style = EventPassTypography.hint, color = EventPassColors.muted)
                QuestionKindMenu(null, enabled) { kind ->
                    change(fields.copy(questions = fields.questions + DraftQuestion(UUID.randomUUID().toString(), kind = kind)))
                }
            }
        }
        if(s.local!=null) EventPassSection {
            Text(stringResource(R.string.draft_sections),style=EventPassTypography.emphasis)
            fields.sections.forEach { section -> key(section.id) {
                FormField(section.title,{text->change(fields.copy(sections=fields.sections.map {if(it.id==section.id) it.copy(title=text) else it}))},stringResource(R.string.draft_section_title),enabled=enabled,
                    error=if("sections" in invalid && section.title.isBlank()) stringResource(R.string.event_field_required) else null)
                TemporalField(section.time,{text->change(fields.copy(sections=fields.sections.map {if(it.id==section.id) it.copy(time=text) else it}))},stringResource(R.string.draft_section_time),enabled=enabled,input=TemporalInput.TIME,allowClear=true)
                TextAction(stringResource(R.string.editor_remove),{change(fields.copy(sections=fields.sections.filterNot {it.id==section.id}))},enabled=enabled)
            } }
            TextAction(stringResource(R.string.draft_section_add),{change(fields.copy(sections=fields.sections+EventSection("","")))},enabled=enabled)
        }
        Text(stringResource(R.string.editor_public_hint), style = EventPassTypography.hint)
        if (s.saved) Text(stringResource(if(s.local!=null) R.string.draft_saved_device else R.string.editor_saved), Modifier.testTag("editor-saved"), color = EventPassColors.success)
        if (s.terminal) Text(stringResource(R.string.editor_terminal))
        EventPassButton(stringResource(if (published) R.string.editor_save_changes else R.string.editor_save), onSave, Modifier.testTag("save-draft"), enabled = enabled, loading = s.busy, style = ActionStyle.Secondary)
        if (!published) EventPassButton(stringResource(R.string.editor_publish), onPublish, Modifier.testTag("publish-event"), enabled = enabled && !s.syncing && s.local?.conflict==null, loading = s.busy)
        if (published && !s.terminal) {
            Text(stringResource(R.string.editor_manage_hint), style = EventPassTypography.hint)
            EventPassButton(stringResource(R.string.editor_finish), { onManage(true) }, Modifier.testTag("finish-event"), enabled = enabled && !s.dirty, style = ActionStyle.Danger)
        }
    }
}
}
}

fun draftStateResource(state: DraftSyncState): Int = when(state) {
    DraftSyncState.UNSAVED -> R.string.editor_unsaved
    DraftSyncState.LOCAL -> R.string.draft_saved_device
    DraftSyncState.SYNCING -> R.string.draft_syncing
    DraftSyncState.SYNCED -> R.string.draft_saved_server
    DraftSyncState.UNCERTAIN -> R.string.draft_uncertain
    DraftSyncState.CONFLICT -> R.string.editor_conflict
    DraftSyncState.PUBLISHED -> R.string.editor_published
}

@Composable
private fun DraftVariant(label: Int, fields: EventFields) {
    Text(stringResource(label),style=EventPassTypography.emphasis)
    Text(fields.title.ifBlank { stringResource(R.string.editor_untitled) })
    Text(fields.venue)
    Text(fields.description)
    Text(stringResource(R.string.draft_variant_dates,fields.startsAt?.toString().orEmpty(),fields.endsAt?.toString().orEmpty(),fields.capacity?.toString().orEmpty()))
    Text(stringResource(R.string.draft_variant_counts,fields.questions.size,fields.sections.size))
    fields.questions.forEach { q -> Text(q.title); q.options.forEach { Text(it.title) } }
    fields.sections.forEach { Text(it.title);Text(it.time) }
}

fun questionKindResource(kind: QuestionKind) = when (kind) {
 QuestionKind.TEXT -> R.string.editor_text; QuestionKind.CHECKBOX -> R.string.editor_checkbox; QuestionKind.SINGLE_CHOICE -> R.string.editor_choice
 QuestionKind.TEXTAREA -> R.string.question_textarea; QuestionKind.EMAIL -> R.string.auth_email; QuestionKind.TEL -> R.string.editor_phone_type
 QuestionKind.NUMBER -> R.string.question_number; QuestionKind.DATE -> R.string.question_date; QuestionKind.SELECT -> R.string.question_select
 QuestionKind.MULTIPLE -> R.string.question_multiple; QuestionKind.UNSUPPORTED -> R.string.event_unsupported_question
}

@Composable
private fun QuestionKindMenu(selected: QuestionKind?, enabled: Boolean, choose: (QuestionKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        EventPassButton(if(selected == null) stringResource(R.string.editor_add_field)
            else stringResource(R.string.editor_field_type, stringResource(questionKindResource(selected))),
            { expanded = true }, Modifier.fillMaxWidth(), style = ActionStyle.Secondary, enabled = enabled)
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)) {
            QuestionKind.entries.filter { it != QuestionKind.UNSUPPORTED }.forEach { kind ->
                DropdownMenuItem(text = { Text(stringResource(questionKindResource(kind))) }, onClick = {
                    expanded = false
                    if(kind != selected) choose(kind)
                })
            }
        }
    }
}

@Composable
private fun QuestionEditor(q: DraftQuestion, index: Int, total: Int, enabled: Boolean,
    edit: (DraftQuestion) -> Unit, remove: () -> Unit, move: (Int) -> Unit, invalid: Boolean = false) {
    Column(Modifier.fillMaxWidth().background(EventPassColors.background, EventPassShapes.section)
        .border(1.dp, EventPassColors.raised, EventPassShapes.section).padding(EventPassDimens.large),
        verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
        Text(stringResource(R.string.editor_field_number, index + 1), style = EventPassTypography.emphasis)
        QuestionKindMenu(q.kind, enabled) { edit(q.copy(kind = it, options = emptyList())) }
        FormField(q.title, { edit(q.copy(title = it)) }, stringResource(R.string.editor_field_title) + " *", enabled = enabled,
            error = if(invalid && q.title.isBlank()) stringResource(R.string.event_field_required) else null)
        FormField(q.placeholder.orEmpty(), { edit(q.copy(placeholder = it.takeIf(String::isNotEmpty))) }, stringResource(R.string.question_placeholder), enabled = enabled)
        FormField(q.help.orEmpty(), { edit(q.copy(help = it.takeIf(String::isNotEmpty))) }, stringResource(R.string.question_help), enabled = enabled,
            supportingText = stringResource(R.string.ui_question_help_explanation))
        SettingsSwitch(q.required, { edit(q.copy(required = it)) }, stringResource(R.string.editor_required), enabled = enabled)
        if (q.kind in setOf(QuestionKind.SINGLE_CHOICE, QuestionKind.SELECT, QuestionKind.MULTIPLE)) {
            HorizontalDivider(color = EventPassColors.raised)
            Text(stringResource(R.string.editor_answer_options), style = EventPassTypography.emphasis)
            if(invalid && q.options.size < 2) FieldMessage(stringResource(R.string.ui_question_options_required))
            q.options.forEachIndexed { optionIndex, option -> key(option.id) {
                Column(Modifier.fillMaxWidth().background(EventPassColors.surface, EventPassShapes.control)
                    .padding(EventPassDimens.small), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FormField(option.title, { text -> edit(q.copy(options = q.options.map { if (it.id == option.id) it.copy(title = text) else it })) },
                    stringResource(R.string.editor_option_number, optionIndex + 1), enabled = enabled,
                    error = if(invalid && option.title.isBlank()) stringResource(R.string.event_field_required) else null)
                FormField(option.hint.orEmpty(), { text -> edit(q.copy(options = q.options.map { if (it.id == option.id) it.copy(hint = text.takeIf(String::isNotEmpty)) else it })) },
                    stringResource(R.string.question_option_hint), enabled = enabled)
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(EventPassDimens.tiny),verticalAlignment=Alignment.CenterVertically) {
                    fun moveOption(direction: Int) { val items = q.options.toMutableList(); val target = optionIndex + direction
                        if (target in items.indices) { java.util.Collections.swap(items, optionIndex, target); edit(q.copy(options = items)) } }
                    MoveArrow(true,{moveOption(-1)},enabled && optionIndex > 0)
                    MoveArrow(false,{moveOption(1)},enabled && optionIndex < q.options.lastIndex)
                    Spacer(Modifier.weight(1f))
                    TextAction(stringResource(R.string.editor_remove), { edit(q.copy(options = q.options.filterNot { it.id == option.id })) }, enabled = enabled)
                }
                }
            } }
            TextAction(stringResource(R.string.editor_add_option), { edit(q.copy(options = q.options + RegistrationQuestion.Option(UUID.randomUUID().toString(), ""))) }, enabled = enabled)
        }
        HorizontalDivider(color = EventPassColors.raised)
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(EventPassDimens.tiny),verticalAlignment=Alignment.CenterVertically) {
            MoveArrow(true,{move(-1)},enabled && index > 0)
            MoveArrow(false,{move(1)},enabled && index < total - 1)
            Spacer(Modifier.weight(1f))
            EventPassButton(stringResource(R.string.editor_remove_field), remove, style = ActionStyle.Danger, enabled = enabled,compact=true)
        }
    }
}

@Composable private fun MoveArrow(up:Boolean,move:()->Unit,enabled:Boolean) {
    androidx.compose.material3.IconButton(onClick=move,enabled=enabled) {
        EventPassIcon(EventPassGlyph.Back,stringResource(if(up) R.string.editor_up else R.string.editor_down),
            Modifier.rotate(if(up) 90f else -90f),tint=if(enabled) EventPassColors.textSecondary else EventPassColors.muted.copy(alpha=0.4f))
    }
}
