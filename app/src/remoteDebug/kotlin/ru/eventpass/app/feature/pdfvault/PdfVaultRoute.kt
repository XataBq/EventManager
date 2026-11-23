package ru.eventpass.app.feature.pdfvault

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.importedticket.FieldEvidence
import ru.eventpass.core.domain.importedticket.TicketField
import java.util.Locale

internal const val PDF_VAULT_ENABLED = true

@Composable
internal fun PdfVaultDestination(owner: String, pickOnOpen: Boolean, onBack: () -> Unit) {
    val vm: PdfVaultViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::import)
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<ImportedTicket>>(emptyList()) }
    LaunchedEffect(owner) { vm.bind(owner) }
    LaunchedEffect(state.busy) {
        if (pickOnOpen && !pickerOpened && !state.busy) {
            pickerOpened = true
            chooser.launch(arrayOf("application/pdf"))
        }
    }
    BackHandler(enabled = state.deleteMode || state.active != null || state.pending.isNotEmpty()) {
        if (!state.busy) when {
            state.deleteMode -> vm.setDeleteMode(false)
            state.pending.isNotEmpty() -> vm.skip()
            else -> vm.open(null)
        }
    }
    val draft = state.pending.firstOrNull()
    val active = state.active
    val activeGroup = active?.let { chosen ->
        state.entries.filter { groupKey(it) == groupKey(chosen) }.sortedBy { it.id }
    }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        PdfVaultBar(
            title = if (draft != null) "Проверьте билет" else "Билеты из PDF",
            showTrash = active == null && draft == null && state.entries.isNotEmpty(),
            deleteMode = state.deleteMode,
            selectedCount = state.selectedIds.size,
            busy = state.busy,
            back = {
                when {
                    state.deleteMode -> vm.setDeleteMode(false)
                    draft != null -> vm.skip()
                    active != null -> vm.open(null)
                    else -> onBack()
                }
            },
            trash = {
                if (state.deleteMode) confirmDelete = state.entries.filter { it.id in state.selectedIds }
                else vm.setDeleteMode(true)
            },
        )
        AuthColumn(if (draft == null && active != null) Modifier.ticketSwipe(active, activeGroup, vm::open) else Modifier) {
        if (state.busy) {
            val progress = state.progress
            if (progress == null) LoadingSkeleton(LoadingLayout.Line)
            else Text(progressText(progress), color = EventPassColors.muted)
            if (progress != null) TextAction("Отменить обработку", vm::cancelImport)
        }
        state.failures.distinct().forEach { InlineError(problemText(it)) }
        when {
            draft != null -> key(draft.document, draft.page) { Review(draft, state, vm) }
            active != null -> {
                StoredTicket(active, activeGroup, vm::open, vm::original, if (state.busy) null else vm::recognizeAgain) { confirmDelete = listOf(active) }
            }
            else -> {
                if (!state.deleteMode) EventPassButton("Выбрать PDF", { chooser.launch(arrayOf("application/pdf")) },
                    Modifier.fillMaxWidth(), enabled = !state.busy)
                if (!state.busy && state.entries.isEmpty()) Text("Импортируйте билет, чтобы сохранить карточку и оригинал на этом устройстве.")
                state.entries.groupBy(::groupKey).values.sortedBy { it.first().fields[TicketField.DATE].orEmpty() }.forEach { group ->
                    EventPassSection {
                        val first = group.first()
                        Text(first.fields.getValue(TicketField.TITLE), style = EventPassTypography.cardTitle)
                        eventSummary(first.fields)
                        group.sortedBy { it.id }.forEach { ticket ->
                            val seat = listOf(TicketField.SECTOR, TicketField.ROW, TicketField.SEAT, TicketField.TICKET_NUMBER)
                                .mapNotNull { field -> ticket.fields[field]?.let { label(field) + " " + it } }.joinToString(" · ")
                            PdfTicketListAction(
                                title = seat.ifBlank { "Билет · стр. " + (ticket.page + 1) },
                                deleteMode = state.deleteMode,
                                selected = ticket.id in state.selectedIds,
                                enabled = !state.busy,
                                onClick = { if (state.deleteMode) vm.toggleSelected(ticket.id) else vm.open(ticket) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
    if (confirmDelete.isNotEmpty()) ConfirmDialog(
        title = if (confirmDelete.size == 1) "Удалить импортированный билет?" else "Удалить выбранные билеты?",
        description = if (confirmDelete.size == 1) confirmDelete.first().fields.getValue(TicketField.TITLE)
            else "Выбрано: " + confirmDelete.size,
        confirmLabel = "Удалить",
        onConfirm = {
            val tickets = confirmDelete
            confirmDelete = emptyList()
            vm.delete(tickets)
        },
        onDismiss = { confirmDelete = emptyList() },
        destructive = true,
    )
}

@Composable
internal fun PdfVaultBar(title: String, showTrash: Boolean, deleteMode: Boolean, selectedCount: Int,
    busy: Boolean, back: () -> Unit, trash: () -> Unit) {
    Surface(color = EventPassColors.background) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = EventPassDimens.large).heightIn(min = EventPassDimens.topBar),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = back, enabled = !busy) {
                EventPassIcon(if (deleteMode) EventPassGlyph.Close else EventPassGlyph.Back,
                    if (deleteMode) "Отменить выбор билетов" else "Назад")
            }
            Text(if (deleteMode) "Выбрано: " + selectedCount else title,
                Modifier.weight(1f).padding(horizontal = EventPassDimens.small), style = EventPassTypography.cardTitle)
            if (showTrash) {
                IconButton(onClick = trash, enabled = !busy && (!deleteMode || selectedCount > 0)) {
                    Surface(
                        shape = EventPassShapes.control,
                        color = if (deleteMode) EventPassColors.danger.copy(alpha = 0.12f) else EventPassColors.background,
                        border = if (deleteMode) BorderStroke(1.dp, EventPassColors.danger.copy(alpha = 0.54f)) else null,
                    ) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            EventPassIcon(EventPassGlyph.Delete,
                                if (deleteMode) "Удалить выбранные билеты" else "Выбрать билеты для удаления",
                                tint = if (deleteMode) EventPassColors.danger else EventPassColors.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PdfTicketListAction(title: String, deleteMode: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (deleteMode) {
        ConsentCheckbox(selected, { onClick() }, title, Modifier.fillMaxWidth(), enabled = enabled)
    } else {
        TextAction(title, onClick, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

@Composable
private fun Review(draft: PdfDraft, state: PdfVaultState, vm: PdfVaultViewModel) {
    var showPdf by remember { mutableStateOf(false) }
    Text(draft.originalName.ifBlank { "PDF" } + " · страница " + (draft.page + 1) + " · осталось " + state.pending.size,
        color = EventPassColors.muted, style = EventPassTypography.hint)
    if (!draft.hasText && draft.candidates.isEmpty()) InlineError("В PDF нет читаемого текста. Заполните поля по оригиналу.")
    TextAction("Посмотреть страницу PDF", { showPdf = true }, enabled = !state.busy)
    TextAction("Распознать страницу заново", vm::recheckCurrentPage, enabled = !state.busy)
    FormField(state.fields[TicketField.TITLE].orEmpty(), { vm.edit(TicketField.TITLE, it) }, "Мероприятие *",
        enabled = !state.busy, supportingText = evidence(draft, TicketField.TITLE))
    TicketField.entries.filterNot { it == TicketField.TITLE }.forEach { field ->
        FormField(state.fields[field].orEmpty(), { vm.edit(field, it) }, label(field),
            enabled = !state.busy, supportingText = evidence(draft, field))
    }
    state.extras.forEach { (label, value) ->
        FormField(value, { vm.editExtra(label, it) }, label, enabled = !state.busy)
    }
    when {
        draft.codes.isEmpty() -> Text("Код не найден. Он останется доступен в оригинале PDF.", color = EventPassColors.muted)
        draft.codes.size > 1 -> {
            InlineError("Найдено несколько кодов. Сверьте их с PDF или сохраните билет только с оригиналом.")
            draft.codes.forEachIndexed { index, code ->
                RadioOption("Код " + (index + 1) + " · " + code.format, null, state.code == code,
                    { vm.selectCode(code) }, enabled = !state.busy)
            }
        }
    }
    if (draft.codes.isNotEmpty()) {
        RadioOption("Оставить код только в оригинале", null, state.codeReviewed && state.code == null,
            { vm.selectCode(null) }, enabled = !state.busy)
        state.code?.let { code ->
            Text("Сверьте выделенный код с оригиналом.", style = EventPassTypography.hint, color = EventPassColors.muted)
            BarcodeImage(code)
        }
    }
    EventPassButton("Сохранить билет", vm::save, Modifier.fillMaxWidth(),
        enabled = !state.busy && state.codeReviewed && !state.fields[TicketField.TITLE].isNullOrBlank())
    TextAction(if (draft.replaces == null) "Пропустить страницу" else "Отменить изменения", vm::skip, enabled = !state.busy)
    if (showPdf) PdfDialog(draft.page, { page -> vm.preview(draft) to (page + 1) }, { showPdf = false }, singlePage = true)
}

private fun evidence(draft: PdfDraft, field: TicketField): String? = draft.candidates[field]?.let { candidate ->
    val prefix = when (candidate.evidence) {
        FieldEvidence.LABEL -> "По подписи"
        FieldEvidence.LAYOUT -> "По расположению полей"
        FieldEvidence.OCR -> "Распознано на изображении"
        FieldEvidence.UNIQUE_VALUE -> "Предположение"
        FieldEvidence.MULTIPLE_VALUES -> "Несколько значений. Уточните"
    }
    prefix + " · " + candidate.sourceLine
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoredTicket(ticket: ImportedTicket, group: List<ImportedTicket>, open: (ImportedTicket?) -> Unit,
    page: suspend (ImportedTicket, Int) -> Pair<Bitmap, Int>, recognizeAgain: (() -> Unit)? = null, delete: () -> Unit) {
    var showPdf by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    TextAction("К списку", { open(null) })
    TicketPageSlide(ticket, group) { pageTicket ->
        val fields = pageTicket.fields
        val isCurrent = pageTicket.id == ticket.id
        Column(verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
            if (group.size > 1) Text(
                ticketPosition(pageTicket, group) + " · листайте влево или вправо",
                style = EventPassTypography.hint, color = EventPassColors.muted,
            )
            EventPassSection {
                Text(fields.getValue(TicketField.TITLE), style = EventPassTypography.heading)
                pageTicket.code?.let { BarcodeImage(it, onOpen = if (isCurrent) ({ showCode = true }) else null) }
                eventSummary(fields)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TicketField.SECTOR, TicketField.ROW, TicketField.SEAT, TicketField.ENTRANCE).forEach { field ->
                        fields[field]?.let { KeyValueRow(label(field), it) }
                    }
                }
                listOf(TicketField.HOLDER, TicketField.TICKET_NUMBER, TicketField.PRICE).forEach { field ->
                fields[field]?.let { KeyValueRow(label(field), it) }
                }
                pageTicket.extras.forEach { (label, value) -> KeyValueRow(label, value) }
                TextAction("Открыть оригинал PDF", { showPdf = true }, Modifier.fillMaxWidth(), enabled = isCurrent)
            }
        }
    }
    recognizeAgain?.let { TextAction("Распознать заново", it) }
    TextAction("Удалить билет", delete)
    if (showPdf) PdfDialog(ticket.page, { page(ticket, it) }, { showPdf = false })
    if (showCode) Dialog(onDismissRequest = { showCode = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val original = window?.attributes?.screenBrightness
            window?.let { it.attributes = it.attributes.apply { screenBrightness = 1f } }
            onDispose { if (original != null) window?.let { it.attributes = it.attributes.apply { screenBrightness = original } } }
        }
        Surface(Modifier.fillMaxWidth().padding(12.dp).testTag("pdf-full-code").ticketSwipe(ticket, group, open),
            shape=EventPassShapes.section,color=EventPassColors.card) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TicketPageSlide(ticket, group) { pageTicket ->
                    Column(verticalArrangement = Arrangement.spacedBy(EventPassDimens.small)) {
                        if (group.size > 1) {
                            Text(ticketPosition(pageTicket, group), style = EventPassTypography.hint)
                            val seat = listOf(TicketField.SECTOR, TicketField.ROW, TicketField.SEAT)
                                .mapNotNull { field -> pageTicket.fields[field]?.let { label(field) + " " + it } }
                                .joinToString(" · ")
                            if (seat.isNotEmpty()) Text(seat, style = EventPassTypography.emphasis)
                        }
                        if (pageTicket.code != null) BarcodeImage(pageTicket.code)
                        else Text("Для этого билета код не выбран. Его можно показать из оригинала PDF.")
                    }
                }
                TextAction("Открыть оригинал PDF", { showCode = false; showPdf = true }, Modifier.fillMaxWidth())
                TextAction("Закрыть", { showCode = false }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TicketPageSlide(ticket: ImportedTicket, group: List<ImportedTicket>, content: @Composable (ImportedTicket) -> Unit) {
    AnimatedContent(
        targetState = ticket,
        contentKey = { it.id },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        label = "PDF ticket swipe",
        transitionSpec = {
            val oldPosition = group.indexOfFirst { it.id == initialState.id }
            val newPosition = group.indexOfFirst { it.id == targetState.id }
            val direction = if (newPosition >= oldPosition) AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right
            val motion = tween<androidx.compose.ui.unit.IntOffset>(260, easing = FastOutSlowInEasing)
            (slideIntoContainer(direction, motion) + fadeIn(tween(180))) togetherWith
                (slideOutOfContainer(direction, motion) + fadeOut(tween(150)))
        },
        content = { pageTicket -> content(pageTicket) },
    )
}

private fun ticketPosition(ticket: ImportedTicket, group: List<ImportedTicket>): String =
    "Билет " + (group.indexOfFirst { it.id == ticket.id } + 1) + " из " + group.size

internal fun Modifier.ticketSwipe(ticket: ImportedTicket, group: List<ImportedTicket>, open: (ImportedTicket) -> Unit): Modifier {
    if (group.size <= 1) return this
    val position = group.indexOfFirst { it.id == ticket.id }
    if (position < 0) return this
    val previous = group.getOrNull(position - 1)
    val next = group.getOrNull(position + 1)
    return pointerInput(ticket.id, previous, next) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { change, delta ->
                change.consume()
                distance += delta
            },
            onDragEnd = {
                val threshold = 42.dp.toPx()
                when {
                    distance < -threshold -> next?.let(open)
                    distance > threshold -> previous?.let(open)
                }
            },
        )
    }
}

@Composable
private fun eventSummary(fields: Map<TicketField, String>) {
    listOfNotNull(fields[TicketField.DATE], fields[TicketField.TIME]).joinToString(" · ")
        .takeIf(String::isNotBlank)?.let { Text(it, style = EventPassTypography.emphasis) }
    fields[TicketField.VENUE]?.let { Text(it, style = EventPassTypography.body) }
    fields[TicketField.ADDRESS]?.let { Text(it, style = EventPassTypography.body, color = EventPassColors.textSecondary) }
}

@Composable
private fun BarcodeImage(code: ImportedCode, onOpen: (() -> Unit)? = null) = key(code) {
    var loading by remember { mutableStateOf(true) }
    val image by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.Default) { codeBitmap(code) }
        loading = false
    }
    image?.let {
        Image(it.asImageBitmap(), "Код билета " + code.format,
            Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .then(if (onOpen == null) Modifier else Modifier.clickable(
                    role = Role.Button, onClickLabel = "Развернуть код билета", onClick = onOpen))
                .background(androidx.compose.ui.graphics.Color.White),
            filterQuality = FilterQuality.None)
    } ?: if (loading) LoadingSkeleton(LoadingLayout.Line)
        else Text("Если код не отображается, откройте оригинал PDF.", color = EventPassColors.muted)
}

@Composable
private fun PdfDialog(firstPage: Int, load: suspend (Int) -> Pair<Bitmap, Int>, dismiss: () -> Unit, singlePage: Boolean = false) {
    var page by remember { mutableIntStateOf(firstPage) }
    var failed by remember { mutableStateOf(false) }
    val result by produceState<Pair<Bitmap, Int>?>(null, page) {
        value = null
        failed = false
        try { value = load(page) } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape=EventPassShapes.section, color=EventPassColors.card) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    if (!singlePage) {
                        TextAction("←", { page-- }, enabled = page > 0 && result != null)
                        TextAction("→", { page++ }, enabled = page + 1 < (result?.second ?: 0))
                    }
                    TextAction("Закрыть", dismiss)
                }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val bitmap = result?.first
                    if (bitmap != null) Image(bitmap.asImageBitmap(),"Страница " + (page + 1),Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height))
                    else if (failed) Text("Не удалось открыть страницу.")
                    else LoadingSkeleton(LoadingLayout.Event)
                }
            }
        }
    }
}

internal fun groupKey(ticket: ImportedTicket): List<String> {
    val values = listOf(TicketField.TITLE, TicketField.DATE, TicketField.TIME, TicketField.VENUE)
        .map { ticket.fields[it].orEmpty().trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
    return if (values[1].isBlank() || values[2].isBlank() || values[3].isBlank()) values + ticket.document else values
}

private fun label(field: TicketField) = when (field) {
    TicketField.TITLE -> "Мероприятие"
    TicketField.DATE -> "Дата"
    TicketField.TIME -> "Время"
    TicketField.VENUE -> "Площадка"
    TicketField.ADDRESS -> "Адрес"
    TicketField.SECTOR -> "Сектор"
    TicketField.ROW -> "Ряд"
    TicketField.SEAT -> "Место"
    TicketField.HOLDER -> "ФИО"
    TicketField.ENTRANCE -> "Вход"
    TicketField.TICKET_NUMBER -> "Номер"
    TicketField.PRICE -> "Цена"
}

private fun problemText(problem: PdfProblem) = when (problem) {
    PdfProblem.UNREADABLE -> "Не удалось прочитать один из PDF."
    PdfProblem.PROTECTED -> "PDF защищён паролем."
    PdfProblem.LIMIT -> "Превышен лимит импорта: до 5 файлов, 12 МБ и 8 страниц на файл."
    PdfProblem.DUPLICATE -> "Этот PDF или его страницы уже добавлены."
    PdfProblem.STORAGE -> "Не удалось сохранить или прочитать локальные билеты."
}

private fun progressText(progress: PdfProgress): String {
    if (progress.stage == PdfStage.OPENING) return "Открываем PDF…"
    val step = when (progress.stage) {
        PdfStage.TEXT -> "Читаем текст"
        PdfStage.CODE -> "Ищем код"
        PdfStage.OCR -> "Распознаём текст на изображении"
        PdfStage.OPENING -> "Открываем PDF"
    }
    return step + " · страница " + progress.page + " из " + progress.total
}
