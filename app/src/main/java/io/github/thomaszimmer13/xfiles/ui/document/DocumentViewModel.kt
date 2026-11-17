package io.github.thomaszimmer13.xfiles.ui.document

import android.app.Application
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.document.DocBlock
import io.github.thomaszimmer13.xfiles.data.document.DocLink
import io.github.thomaszimmer13.xfiles.data.document.Document
import io.github.thomaszimmer13.xfiles.data.document.DocumentException
import io.github.thomaszimmer13.xfiles.data.document.DocumentReader
import io.github.thomaszimmer13.xfiles.data.document.LinkKind
import io.github.thomaszimmer13.xfiles.data.document.PdfText
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/** Pourquoi le document n'a pas pu s'afficher. */
enum class DocProblem { NOT_FOUND, UNREADABLE, PDF_IMAGES, PASSWORD, WRONG_PASSWORD }

data class DocumentUiState(
    val path: String = "",
    val loading: Boolean = true,
    val document: Document? = null,
    val problem: DocProblem? = null,
    /** Numéros des blocs qui contiennent la recherche. */
    val matches: List<Int> = emptyList(),
    val query: String = "",
    val currentMatch: Int = -1,
    /** Bloc où poser le focus (reprise, sommaire, recherche). */
    val focusBlock: Int? = null,
)

class DocumentViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as XFilesApplication).container.preferences
    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    val textScale = preferences.docTextScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    private var normalized: List<String> = emptyList()
    private var addressJob: Job? = null
    private var loadedStamp = 0L
    private val pdf by lazy { PdfText(getApplication()) }

    fun load(path: String, password: String? = null, force: Boolean = false) {
        val stamp = File(path).lastModified()
        if (!force && _state.value.path == path && stamp == loadedStamp && (_state.value.document != null || _state.value.loading)) return
        loadedStamp = stamp
        _state.value = DocumentUiState(path = path)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val file = File(path)
            try {
                val doc = withContext(Dispatchers.IO) {
                    DocumentReader.read(file) { pdf.read(it, password) }
                }
                normalized = doc.blocks.map { SearchViewModel.normalize(it.text) }
                val resume = preferences.docPositions.first()[path]?.takeIf { it in doc.blocks.indices && it > 0 }
                _state.update { it.copy(loading = false, document = doc, focusBlock = resume) }
                val intro = buildList {
                    if (resume != null) add(app.getString(R.string.doc_resumed, sectionOf(doc, resume)))
                    if (doc.truncated) add(app.getString(R.string.doc_truncated))
                }
                if (intro.isNotEmpty()) announcer.announce(intro.joinToString(" "))
                findAddresses(doc)
            } catch (_: PdfText.PasswordRequired) {
                _state.update { it.copy(loading = false, problem = if (password == null) DocProblem.PASSWORD else DocProblem.WRONG_PASSWORD) }
            } catch (e: DocumentException) {
                _state.update { it.copy(loading = false, problem = if (e.message == "pdf-image") DocProblem.PDF_IMAGES else DocProblem.UNREADABLE) }
            } catch (_: java.io.FileNotFoundException) {
                _state.update { it.copy(loading = false, problem = DocProblem.NOT_FOUND) }
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, problem = DocProblem.UNREADABLE) }
            } catch (_: OutOfMemoryError) {
                _state.update { it.copy(loading = false, problem = DocProblem.UNREADABLE) }
            }
        }
    }

    /** « section Chapitre 2 » ou « page 4 » : où se trouve le bloc [index]. */
    fun sectionOf(doc: Document, index: Int): String {
        val app = getApplication<Application>()
        for (i in index downTo 0) {
            when (val b = doc.blocks[i]) {
                is DocBlock.Heading -> return app.getString(R.string.doc_section, b.text)
                is DocBlock.PageStart -> return app.getString(R.string.doc_page_n, b.number, doc.pages)
                else -> Unit
            }
        }
        return app.getString(R.string.doc_block_n, index + 1, doc.blocks.size)
    }

    /**
     * Adresses postales : repérées par le système d'Android (le même que pour les notifications),
     * en arrière-plan après l'affichage, sur les 3 000 premiers paragraphes.
     */
    private fun findAddresses(doc: Document) {
        addressJob?.cancel()
        addressJob = viewModelScope.launch(Dispatchers.Default) {
            val classifier = runCatching {
                getApplication<Application>().getSystemService(TextClassificationManager::class.java).textClassifier
            }.getOrNull() ?: return@launch
            if (classifier == TextClassifier.NO_OP) return@launch
            val config = TextClassifier.EntityConfig.Builder()
                .setIncludedTypes(listOf(TextClassifier.TYPE_ADDRESS))
                .includeTypesFromTextClassifier(false)
                .build()
            val updated = doc.blocks.toMutableList()
            var changed = false
            for ((i, block) in doc.blocks.withIndex().take(3_000)) {
                val text = block.text
                if (text.length < 10 || text.length > classifier.maxGenerateLinksTextLength) continue
                if (block !is DocBlock.Paragraph && block !is DocBlock.ListItem) continue
                if (!text.any(Char::isDigit)) continue
                val links = runCatching {
                    classifier.generateLinks(TextLinks.Request.Builder(text).setEntityConfig(config).build()).links
                }.getOrNull() ?: continue
                val addresses = links.filter { it.getConfidenceScore(TextClassifier.TYPE_ADDRESS) > 0.5f }.map { link ->
                    val address = text.substring(link.start, link.end)
                    DocLink(link.start, link.end, "geo:0,0?q=" + URLEncoder.encode(address, "UTF-8"), LinkKind.ADDRESS)
                }
                if (addresses.isEmpty()) continue
                updated[i] = when (block) {
                    is DocBlock.Paragraph -> block.copy(links = mergeLinks(block.links, addresses))
                    is DocBlock.ListItem -> block.copy(links = mergeLinks(block.links, addresses))
                    else -> block
                }
                changed = true
            }
            if (changed) _state.update { s -> s.copy(document = s.document?.copy(blocks = updated)) }
        }
    }

    private fun mergeLinks(existing: List<DocLink>, added: List<DocLink>) =
        (existing + added.filter { a -> existing.none { a.start < it.end && a.end > it.start } }).sortedBy { it.start }

    // --- Recherche dans le document ---

    private var searchJob: Job? = null

    /** Cherche pendant la frappe ; le nombre de résultats est annoncé une fois la frappe arrêtée. */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            val q = SearchViewModel.normalize(query.trim())
            val source = normalized
            val matches = withContext(Dispatchers.Default) {
                if (q.isEmpty()) emptyList() else source.indices.filter { q in source[it] }
            }
            _state.update { it.copy(matches = matches, currentMatch = -1) }
            announceMatchCount()
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", matches = emptyList(), currentMatch = -1) }
    }

    /** Résultat suivant ([forward]) ou précédent : focus dessus et annonce « Résultat 2 sur 7 ». */
    fun nextMatch(forward: Boolean) {
        val s = _state.value
        if (s.matches.isEmpty()) {
            announcer.announce(getApplication<Application>().getString(R.string.doc_search_none))
            return
        }
        val next = when {
            s.currentMatch < 0 -> if (forward) 0 else s.matches.lastIndex
            forward -> (s.currentMatch + 1) % s.matches.size
            else -> (s.currentMatch - 1 + s.matches.size) % s.matches.size
        }
        _state.update { it.copy(currentMatch = next, focusBlock = s.matches[next]) }
        announcer.announce(getApplication<Application>().getString(R.string.doc_search_position, next + 1, s.matches.size))
    }

    fun announceMatchCount() {
        val s = _state.value
        if (s.query.isBlank()) return
        val res = getApplication<Application>().resources
        announcer.announce(
            if (s.matches.isEmpty()) res.getString(R.string.doc_search_none) else res.getQuantityString(R.plurals.doc_search_count, s.matches.size, s.matches.size),
        )
    }

    fun jumpTo(block: Int) {
        _state.update { it.copy(focusBlock = block) }
    }

    fun consumeFocus() = _state.update { it.copy(focusBlock = null) }

    /** Mémorise le premier bloc visible pour reprendre la lecture plus tard. */
    fun savePosition(block: Int) {
        val path = _state.value.path
        if (path.isEmpty() || _state.value.document == null) return
        viewModelScope.launch { preferences.setDocPosition(path, block) }
    }

    fun setTextScale(scale: Float) {
        viewModelScope.launch { preferences.setDocTextScale(scale) }
    }

    fun announce(text: String) = announcer.announce(text)

    private companion object {
        const val SEARCH_DELAY_MS = 700L
    }
}
