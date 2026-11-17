package io.github.thomaszimmer13.xfiles.data.analysis

import io.github.thomaszimmer13.xfiles.data.media.Categories
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Fichier trouvé par l'analyse. */
data class FoundFile(val path: String, val name: String, val size: Long, val lastModified: Long)

/** Fichiers identiques (même contenu), du plus ancien au plus récent. */
data class DuplicateGroup(val size: Long, val files: List<FoundFile>) {
    /** Place libérée en ne gardant qu'un exemplaire. */
    val wasted: Long get() = size * (files.size - 1)
}

/** Dossier de premier niveau et ce qu'il occupe. */
data class FolderUsage(val path: String, val name: String, val bytes: Long, val files: Int)

data class AnalysisResult(
    val scannedFiles: Int,
    val scannedBytes: Long,
    val bigFiles: List<FoundFile>,
    val duplicates: List<DuplicateGroup>,
    val oldDownloads: List<FoundFile>,
    val heaviestFolders: List<FolderUsage>,
    val finishedAt: Long,
)

data class AnalysisState(
    val running: Boolean = false,
    /** Fichiers parcourus jusqu'ici (affiché pendant l'analyse). */
    val scanned: Int = 0,
    /** Étape en cours : parcours, puis recherche des doublons. */
    val comparing: Boolean = false,
    val result: AnalysisResult? = null,
)

/**
 * Analyse du stockage : gros fichiers, doublons, anciens téléchargements, dossiers les plus lourds.
 * Tourne dans la portée de l'app : elle continue si l'on change d'écran, et son résultat reste disponible.
 * Les dossiers cachés, les données des apps et la corbeille sont ignorés.
 */
class StorageAnalyzer(private val volumeRoots: () -> List<String>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, scanned = 0, comparing = false) }
        job = scope.launch {
            try {
                val result = analyze(volumeRoots(), Categories.downloadsDir().path, System.currentTimeMillis()) { scanned ->
                    _state.update { it.copy(scanned = scanned) }
                }
                _state.value = AnalysisState(result = result)
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false, comparing = false) }
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(running = false, comparing = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Retire des résultats les fichiers supprimés ou mis à la corbeille. */
    fun forget(paths: Set<String>) {
        _state.update { s ->
            val r = s.result ?: return@update s
            s.copy(
                result = r.copy(
                    bigFiles = r.bigFiles.filter { it.path !in paths },
                    oldDownloads = r.oldDownloads.filter { it.path !in paths },
                    duplicates = r.duplicates.map { g -> g.copy(files = g.files.filter { it.path !in paths }) }.filter { it.files.size > 1 },
                ),
            )
        }
    }

    internal suspend fun analyze(
        roots: List<String>,
        downloads: String,
        now: Long,
        onProgress: (Int) -> Unit = {},
    ): AnalysisResult {
        val all = ArrayList<FoundFile>()
        val folders = mutableListOf<FolderUsage>()
        var lastReport = 0L
        for (root in roots) {
            for (top in File(root).listFiles().orEmpty()) {
                if (!keep(top)) continue
                if (top.isFile) {
                    all += found(top)
                    continue
                }
                var bytes = 0L
                var count = 0
                val pending = ArrayDeque<File>().apply { add(top) }
                while (pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    for (child in pending.removeFirst().listFiles().orEmpty()) {
                        if (!keep(child)) continue
                        if (child.isDirectory) {
                            pending.add(child)
                        } else {
                            val f = found(child)
                            all += f
                            bytes += f.size
                            count++
                        }
                    }
                    val t = System.currentTimeMillis()
                    if (t - lastReport > 300) {
                        lastReport = t
                        onProgress(all.size)
                    }
                }
                folders += FolderUsage(top.path, top.name, bytes, count)
            }
        }
        onProgress(all.size)
        _state.update { it.copy(comparing = true) }

        val oldLimit = now - TimeUnit.DAYS.toMillis(OLD_DOWNLOAD_DAYS)
        return AnalysisResult(
            scannedFiles = all.size,
            scannedBytes = all.sumOf { it.size },
            bigFiles = all.filter { it.size >= BIG_FILE_BYTES }.sortedByDescending { it.size }.take(MAX_LIST),
            duplicates = findDuplicates(all),
            oldDownloads = all.filter { it.path.startsWith("$downloads/") && it.lastModified < oldLimit }
                .sortedBy { it.lastModified }.take(MAX_LIST),
            heaviestFolders = folders.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(MAX_FOLDERS),
            finishedAt = now,
        )
    }

    /** Même taille → mêmes 64 premiers kilooctets → même SHA-256 complet. */
    internal suspend fun findDuplicates(files: List<FoundFile>): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        for ((size, sameSize) in files.filter { it.size >= MIN_DUPLICATE_BYTES }.groupBy { it.size }) {
            if (sameSize.size < 2) continue
            val byStart = sameSize.groupBy { runCatching { FileStats.partialSha256(File(it.path)) }.getOrNull() }
            for ((start, candidates) in byStart) {
                if (start == null || candidates.size < 2) continue
                coroutineContext.ensureActive()
                val byFull = if (size <= PARTIAL_IS_FULL) {
                    mapOf(start to candidates)
                } else {
                    candidates.groupBy { runCatching { FileStats.sha256(File(it.path)) }.getOrNull() }
                }
                for ((hash, same) in byFull) {
                    if (hash != null && same.size > 1) groups += DuplicateGroup(size, same.sortedBy { it.lastModified })
                }
            }
        }
        return groups.sortedByDescending { it.wasted }.take(MAX_LIST)
    }

    private fun keep(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".") || name == LocalTrash.DIR_NAME) return false
        if (FileStats.isSymlink(file)) return false
        val path = file.path
        return !path.contains("/Android/data") && !path.contains("/Android/obb")
    }

    private fun found(file: File) = FoundFile(file.path, file.name, file.length(), file.lastModified())

    companion object {
        const val BIG_FILE_BYTES = 50L * 1024 * 1024
        const val MIN_DUPLICATE_BYTES = 100L * 1024
        const val OLD_DOWNLOAD_DAYS = 90L
        private const val PARTIAL_IS_FULL = 64L * 1024
        private const val MAX_LIST = 300
        private const val MAX_FOLDERS = 15
    }
}
