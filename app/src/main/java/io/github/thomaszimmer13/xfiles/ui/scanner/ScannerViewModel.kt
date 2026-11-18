package io.github.thomaszimmer13.xfiles.ui.scanner

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.ocr.Guidance
import io.github.thomaszimmer13.xfiles.data.ocr.OcrModelState
import io.github.thomaszimmer13.xfiles.data.ocr.ScanGuide
import io.github.thomaszimmer13.xfiles.data.ocr.ScanPdf
import io.github.thomaszimmer13.xfiles.data.ocr.ScannedPage
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ScannerUiState(
    val pages: List<ScannedPage> = emptyList(),
    val guidance: Guidance? = null,
    /** Photo en cours de reconnaissance. */
    val processing: Boolean = false,
    val saving: Boolean = false,
    val autoCapture: Boolean = true,
    val torch: Boolean = false,
    /** PDF enregistré : le lecteur l'ouvre. */
    val saved: File? = null,
)

/**
 * Scanner guidé : l'aperçu est analysé deux fois par seconde (détection seule, rapide) ; le conseil
 * est annoncé quand il change, jamais plus d'une fois toutes les deux secondes et demie. Page bien
 * cadrée deux fois de suite : photo automatique (ou bouton « Prendre la photo »).
 */
class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as XFilesApplication).container
    private val ocr = container.ocr
    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()
    val announcer = Announcer(viewModelScope)
    val modelState = ocr.state
    val modelSize = ocr.downloadSize

    private val analyzing = AtomicBoolean(false)
    private var readyStreak = 0
    private var lastSpoken: Guidance? = null
    private var lastSpokenAt = 0L
    private var cooldownUntil = 0L
    /** Après une photo, il faut que le cadre change (page tournée) avant la photo automatique suivante. */
    private var needsChange = false
    private val workDir = File(application.cacheDir, "scan/${System.currentTimeMillis()}").apply { mkdirs() }

    /** Demandé par l'écran quand la capture automatique décide de prendre la photo. */
    var onAutoCapture: (() -> Unit)? = null

    fun setAutoCapture(on: Boolean) = _state.update { it.copy(autoCapture = on) }

    fun setTorch(on: Boolean) = _state.update { it.copy(torch = on) }

    fun downloadModels() = ocr.download()

    /** Image d'aperçu déjà tournée comme l'écran ; ignorée si l'analyse précédente n'est pas finie. */
    fun onFrame(bitmap: Bitmap) {
        val s = _state.value
        if (ocr.state.value !is OcrModelState.Ready || s.processing || s.saving || !analyzing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        viewModelScope.launch {
            try {
                val boxes = ocr.detect(bitmap, 640)
                val guidance = ScanGuide.evaluate(boxes, bitmap.width, bitmap.height)
                update(guidance)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Image illisible : on attend la suivante.
            } finally {
                bitmap.recycle()
                analyzing.set(false)
            }
        }
    }

    private fun update(guidance: Guidance) {
        _state.update { it.copy(guidance = guidance) }
        val now = System.currentTimeMillis()
        if (guidance != Guidance.READY) {
            readyStreak = 0
            needsChange = false
        } else {
            readyStreak++
        }
        val app = getApplication<Application>()
        val auto = _state.value.autoCapture && !needsChange && now >= cooldownUntil
        if (guidance == Guidance.READY && auto && readyStreak >= 2) {
            readyStreak = 0
            onAutoCapture?.invoke()
            return
        }
        // Annonce : seulement si le conseil change, et pas plus d'une fois toutes les 2,5 s.
        if (guidance != lastSpoken && now - lastSpokenAt > SPEAK_INTERVAL_MS) {
            lastSpoken = guidance
            lastSpokenAt = now
            val text = when (guidance) {
                Guidance.READY -> app.getString(if (auto) R.string.scan_ready_auto else R.string.scan_ready)
                else -> app.getString(guidanceText(guidance))
            }
            announcer.announce(text)
        }
    }

    /** Photo prise (automatique ou bouton) : reconnaissance puis ajout de la page. */
    fun onCaptured(bitmap: Bitmap) {
        if (_state.value.processing) {
            bitmap.recycle()
            return
        }
        _state.update { it.copy(processing = true) }
        container.feedback.success()
        val app = getApplication<Application>()
        announcer.announce(app.getString(R.string.scan_captured))
        viewModelScope.launch {
            try {
                val number = _state.value.pages.size + 1
                val file = File(workDir, "page-$number.jpg")
                withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                val page = ocr.recognize(bitmap)
                val scanned = ScannedPage(file, bitmap.width, bitmap.height, page.lines, page.paragraphs)
                _state.update { it.copy(pages = it.pages + scanned, processing = false) }
                val start = page.paragraphs.joinToString(" ").take(80).substringBeforeLast(' ', page.paragraphs.joinToString(" ").take(80))
                announcer.announce(
                    if (page.paragraphs.isEmpty()) app.getString(R.string.scan_page_added_empty, number)
                    else app.getString(R.string.scan_page_added, number, start),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(processing = false) }
                announcer.announce(app.getString(R.string.ocr_failed))
            } finally {
                bitmap.recycle()
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                needsChange = true
                lastSpoken = null
            }
        }
    }

    fun captureFailed() = announcer.announce(getApplication<Application>().getString(R.string.scan_capture_failed))

    fun removeLastPage() {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        pages.last().image.delete()
        _state.update { it.copy(pages = pages.dropLast(1)) }
        announcer.announce(getApplication<Application>().resources.getQuantityString(R.plurals.scan_page_removed, pages.size - 1, pages.size - 1))
    }

    /** Enregistre le PDF dans [directory] (ou Documents si on ne peut pas y écrire). */
    fun save(directory: String) {
        val pages = _state.value.pages
        if (pages.isEmpty() || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        val app = getApplication<Application>()
        announcer.announce(app.getString(R.string.scan_saving))
        viewModelScope.launch {
            val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.FRANCE).format(Date())
            val target = withContext(Dispatchers.IO) {
                val dirs = listOf(File(directory), android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS))
                for (dir in dirs) {
                    if (!dir.canWrite()) continue
                    var f = File(dir, "$name.pdf")
                    var n = 2
                    while (f.exists()) f = File(dir, "$name ($n).pdf").also { n++ }
                    val ok = runCatching { ScanPdf.write(app, pages, f, name) }.isSuccess
                    if (ok) return@withContext f
                    f.delete()
                }
                null
            }
            if (target != null) {
                // Le lecteur ouvrira tout de suite le texte reconnu, sans relire le PDF.
                withContext(Dispatchers.IO) {
                    ocr.store(target, pages.map { it.paragraphs })
                    workDir.deleteRecursively()
                }
                container.feedback.success()
                announcer.announce(app.resources.getQuantityString(R.plurals.scan_saved, pages.size, target.name, pages.size))
                _state.update { it.copy(saving = false, saved = target, pages = emptyList()) }
            } else {
                container.feedback.failure()
                _state.update { it.copy(saving = false) }
                announcer.announce(app.getString(R.string.scan_save_failed))
            }
        }
    }

    fun discard() {
        _state.value.pages.forEach { it.image.delete() }
        _state.update { it.copy(pages = emptyList()) }
    }

    fun consumeSaved() = _state.update { it.copy(saved = null) }

    override fun onCleared() {
        workDir.deleteRecursively()
    }

    companion object {
        private const val SPEAK_INTERVAL_MS = 2_500L
        private const val COOLDOWN_MS = 3_000L

        fun guidanceText(g: Guidance): Int = when (g) {
            Guidance.NO_TEXT -> R.string.scan_no_text
            Guidance.CUT_TOP -> R.string.scan_cut_top
            Guidance.CUT_BOTTOM -> R.string.scan_cut_bottom
            Guidance.CUT_LEFT -> R.string.scan_cut_left
            Guidance.CUT_RIGHT -> R.string.scan_cut_right
            Guidance.MOVE_BACK -> R.string.scan_move_back
            Guidance.MOVE_CLOSER -> R.string.scan_move_closer
            Guidance.READY -> R.string.scan_ready
        }
    }
}
