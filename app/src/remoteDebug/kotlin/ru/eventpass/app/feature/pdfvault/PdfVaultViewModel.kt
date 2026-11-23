package ru.eventpass.app.feature.pdfvault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.eventpass.core.domain.importedticket.TicketField
import javax.inject.Inject

internal data class PdfVaultState(
    val entries: List<ImportedTicket> = emptyList(),
    val pending: List<PdfDraft> = emptyList(),
    val fields: Map<TicketField, String> = emptyMap(),
    val extras: Map<String, String> = emptyMap(),
    val code: ImportedCode? = null,
    val codeReviewed: Boolean = false,
    val active: ImportedTicket? = null,
    val deleteMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val busy: Boolean = true,
    val progress: PdfProgress? = null,
    val failures: List<PdfProblem> = emptyList(),
)

@HiltViewModel
internal class PdfVaultViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(PdfVaultState())
    val state = mutable.asStateFlow()
    private lateinit var storage: PdfVaultStorage
    private var account: String? = null
    private var work: Job? = null

    fun bind(owner: String) {
        if (account == owner) return
        check(account == null)
        account = owner
        storage = PdfVaultStorage(context, owner)
        runWork { mutable.value = state.value.copy(entries = withContext(Dispatchers.IO) { storage.list() }) }
    }

    fun import(files: List<Uri>) {
        if (files.isEmpty() || state.value.pending.isNotEmpty() || state.value.deleteMode || account == null) return
        runWork {
            val failures = mutableListOf<PdfProblem>()
            if (files.size > 5) failures += PdfProblem.LIMIT
            val drafts = mutableListOf<PdfDraft>()
            try {
                files.take(5).forEach { uri ->
                    try {
                        withContext(Dispatchers.IO) {
                            val pages = storage.read(uri, ::showProgress)
                            if (drafts.any { it.document == pages.first().document }) {
                                pages.first().file.delete()
                                failures += PdfProblem.DUPLICATE
                            } else drafts += pages
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures += (error as? PdfImportFailure)?.problem ?: PdfProblem.UNREADABLE
                    }
                }
                showPending(drafts.toList(), failures)
            } catch (cancelled: CancellationException) {
                drafts.distinctBy { it.file }.forEach { it.file.delete() }
                throw cancelled
            }
        }
    }

    fun edit(field: TicketField, value: String) {
        if (!state.value.busy) mutable.value = state.value.copy(fields = state.value.fields + (field to value.take(300)))
    }

    fun selectCode(code: ImportedCode?) {
        if (!state.value.busy) mutable.value = state.value.copy(code = code, codeReviewed = true)
    }

    fun editExtra(label: String, value: String) {
        if (!state.value.busy && label in state.value.extras)
            mutable.value = state.value.copy(extras = state.value.extras + (label to value.take(300)))
    }

    fun recognizeAgain() {
        val ticket = state.value.active ?: return
        runWork {
            showProgress(PdfProgress(PdfStage.OPENING))
            val draft = withContext(Dispatchers.IO) { storage.reread(ticket, ::showProgress) }
            showPending(listOf(draft), emptyList())
        }
    }

    fun recheckCurrentPage() {
        val previous = state.value.pending.firstOrNull() ?: return
        runWork {
            val refreshed = withContext(Dispatchers.IO) { storage.recheck(previous, ::showProgress) }
            val current = state.value
            val chosenCode = current.code?.takeIf { current.codeReviewed && previous.codes.size > 1 && it in refreshed.codes }
            val keepOriginalOnly = current.codeReviewed && current.code == null && previous.codes.isNotEmpty()
            val suggestedCode = if (keepOriginalOnly) null else chosenCode ?: refreshed.codes.singleOrNull()
            val suggestions = refreshed.candidates.mapValues { it.value.value }.filterValues(String::isNotBlank)
            mutable.value = current.copy(
                pending = listOf(refreshed) + current.pending.drop(1),
                fields = suggestions + current.fields,
                extras = refreshed.extras + current.extras,
                code = suggestedCode,
                codeReviewed = keepOriginalOnly || chosenCode != null || refreshed.codes.size <= 1,
            )
        }
    }

    fun skip() {
        if (state.value.busy) return
        advance()
    }

    fun cancelImport() {
        if (state.value.progress != null) work?.cancel()
    }

    private fun showProgress(progress: PdfProgress) {
        mutable.update { it.copy(progress = progress) }
    }

    fun save() {
        val snapshot = state.value
        val draft = snapshot.pending.firstOrNull() ?: return
        if (snapshot.fields[TicketField.TITLE].isNullOrBlank() || !snapshot.codeReviewed) return
        runWork {
            val fields = snapshot.fields.mapValues { it.value.trim() }.filterValues { it.isNotBlank() }
            val extras = snapshot.extras.mapValues { it.value.trim() }.filterValues(String::isNotBlank)
            val ticket = withContext(Dispatchers.IO) { storage.save(draft, fields, snapshot.code, extras) }
            mutable.value = state.value.copy(
                entries = state.value.entries.filterNot { it.id == ticket.id } + ticket,
                active = state.value.active?.let { if (it.id == ticket.id) ticket else it },
            )
            advance()
        }
    }

    fun open(ticket: ImportedTicket?) {
        if (!state.value.busy && !state.value.deleteMode)
            mutable.value = state.value.copy(active = ticket, failures = emptyList())
    }

    fun setDeleteMode(enabled: Boolean) {
        val current = state.value
        if (!current.busy && current.active == null && current.pending.isEmpty())
            mutable.value = current.copy(deleteMode = enabled, selectedIds = emptySet())
    }

    fun toggleSelected(id: String) {
        val current = state.value
        if (current.busy || !current.deleteMode || current.entries.none { it.id == id }) return
        val selected = current.selectedIds
        mutable.value = current.copy(selectedIds = if (id in selected) selected - id else selected + id)
    }

    fun delete(tickets: List<ImportedTicket>) = runWork {
        val (remaining, failure) = withContext(Dispatchers.IO) {
            val existing = storage.list().associateBy { it.id }
            var problem: PdfProblem? = null
            tickets.mapNotNull { existing[it.id] }.forEach { ticket ->
                try { storage.delete(ticket) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    problem = (error as? PdfImportFailure)?.problem ?: PdfProblem.STORAGE
                }
            }
            storage.list() to problem
        }
        val ids = remaining.map { it.id }.toSet()
        val current = state.value
        val selected = current.selectedIds intersect ids
        mutable.value = current.copy(
            entries = remaining,
            active = current.active?.takeIf { it.id in ids },
            deleteMode = current.deleteMode && selected.isNotEmpty(),
            selectedIds = selected,
            failures = listOfNotNull(failure),
        )
    }

    suspend fun original(ticket: ImportedTicket, page: Int): Pair<Bitmap, Int> =
        withContext(Dispatchers.IO) { storage.page(ticket, page) }

    suspend fun preview(draft: PdfDraft): Bitmap = withContext(Dispatchers.IO) { storage.preview(draft) }

    private fun advance() {
        val draft = state.value.pending.firstOrNull() ?: return
        val rest = state.value.pending.drop(1)
        if (draft.replaces == null && rest.none { it.file == draft.file }) draft.file.delete()
        showPending(rest, state.value.failures)
    }

    private fun showPending(pending: List<PdfDraft>, failures: List<PdfProblem>) {
        val first = pending.firstOrNull()
        val previous = first?.replaces
        mutable.value = state.value.copy(
            pending = pending,
            fields = previous?.fields.orEmpty() + first?.candidates.orEmpty().mapValues { it.value.value }.filterValues(String::isNotBlank),
            extras = previous?.extras.orEmpty() + first?.extras.orEmpty(),
            code = first?.codes?.singleOrNull() ?: previous?.code?.takeIf { first?.codes?.contains(it) == true },
            codeReviewed = first == null || first.codes.size <= 1 || previous?.code?.let { it in first.codes } == true,
            failures = failures,
        )
    }

    private fun runWork(action: suspend () -> Unit) {
        if (work?.isActive == true) return
        mutable.value = state.value.copy(busy = true, failures = emptyList())
        work = viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value = state.value.copy(failures = listOf((error as? PdfImportFailure)?.problem ?: PdfProblem.STORAGE))
            } finally {
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    override fun onCleared() {
        work?.cancel()
        state.value.pending.filter { it.replaces == null }.distinctBy { it.file }.forEach { it.file.delete() }
    }
}
