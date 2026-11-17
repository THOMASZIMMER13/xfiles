package io.github.thomaszimmer13.xfiles.data.transfer

import android.app.Application
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.data.archive.ZipArchive
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.ui.explorer.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class TransferKind { COPY, MOVE, EXTRACT, COMPRESS }

/** Où en est l'opération en cours (affichée dans l'app et dans la notification). */
data class TransferProgress(
    val kind: TransferKind,
    /** « 3 éléments », « Photos.zip »… */
    val subject: String,
    /** Nom du dossier de destination. */
    val folder: String,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val currentName: String = "",
) {
    val percent: Int get() = if (totalBytes <= 0) 0 else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** Fin d'une opération : message à annoncer, dossier concerné, élément sur lequel poser le focus. */
data class TransferOutcome(val message: String, val targetDir: String, val focusPath: String?)

/** Question posée quand un nom existe déjà : [othersRemaining] conflits suivent celui-ci. */
data class ConflictPrompt(val name: String, val isDirectory: Boolean, val folder: String, val othersRemaining: Int)

data class ConflictAnswer(val choice: ConflictChoice?, val applyToAll: Boolean) {
    /** Annuler l'opération (choix nul). */
    val cancel: Boolean get() = choice == null
}

/**
 * Exécute les opérations longues (coller, extraire, compresser) hors des écrans : elles continuent quand on
 * change de dossier, d'onglet, ou qu'on quitte l'app ([TransferService] garde l'app en vie et affiche
 * la progression). Une seule opération à la fois.
 */
class TransferManager(
    private val app: Application,
    private val preferences: PreferencesRepository,
    private val clipboard: FileClipboard,
    private val transfer: LocalTransfer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _conflict = MutableStateFlow<ConflictPrompt?>(null)
    val conflict: StateFlow<ConflictPrompt?> = _conflict.asStateFlow()

    /** Messages à lire : début d'une opération qui dure, puis bilan. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _outcomes = MutableSharedFlow<TransferOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<TransferOutcome> = _outcomes.asSharedFlow()

    /** Dernier bilan, repris par la notification de fin quand l'app est en arrière-plan. */
    @Volatile var lastOutcome: TransferOutcome? = null
        private set

    @Volatile private var cancelRequested = false
    private var job: Job? = null
    private var pendingConflict: CompletableDeferred<ConflictAnswer>? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun cancel() {
        cancelRequested = true
        pendingConflict?.complete(ConflictAnswer(null, false))
    }

    fun answerConflict(answer: ConflictAnswer) {
        _conflict.value = null
        pendingConflict?.complete(answer)
        pendingConflict = null
    }

    /** Colle le presse-papiers dans [dir]. Renvoie false si une opération est déjà en cours. */
    fun paste(clip: ClipContent, dir: String, folder: String): Boolean {
        val kind = if (clip.mode == TransferMode.COPY) TransferKind.COPY else TransferKind.MOVE
        val subject = subjectFor(clip.items.size, clip.items.first().name)
        return launchJob(kind, subject, folder, total = { clip.items.sumOf { sizeOf(File(it.path)) } }) { tracker ->
            pasteItems(clip, dir, folder, tracker)
        }
    }

    /** Extrait [prefix] (null = toute l'archive) de [zipPath] dans [targetDir]. */
    fun extract(zipPath: String, prefix: String?, targetDir: String, folder: String): Boolean {
        val subject = prefix?.substringAfterLast('/') ?: File(zipPath).name
        return launchJob(TransferKind.EXTRACT, subject, folder, total = {
            ZipArchive.uncompressedSize(ZipArchive.list(zipPath), prefix)
        }) { tracker ->
            val created = ZipArchive.extract(zipPath, prefix, File(targetDir), tracker)
            TransferOutcome(app.getString(R.string.extracted, subject, File(created).name, folder), targetDir, created)
        }
    }

    /** Crée une archive zip avec [sources] dans [targetDir]. */
    fun compress(sources: List<String>, targetDir: String, folder: String): Boolean {
        val files = sources.map(::File)
        val subject = subjectFor(files.size, files.first().name)
        return launchJob(TransferKind.COMPRESS, subject, folder, total = { files.sumOf(::sizeOf) }) { tracker ->
            val dir = File(targetDir)
            val name = ZipArchive.archiveNameFor(files, dir.list()?.toList().orEmpty(), app.getString(R.string.archive_default_name))
            val target = File(dir, name)
            ZipArchive.compress(files, target, tracker)
            TransferOutcome(app.getString(R.string.compressed, name, folder), targetDir, target.path)
        }
    }

    private fun subjectFor(count: Int, firstName: String): String =
        if (count == 1) firstName else app.resources.getQuantityString(R.plurals.items_count, count, count)

    /** Suivi des octets ; la progression publiée est mise à jour au plus toutes les 250 ms. */
    private inner class Tracker : TransferListener {
        private var lastPublish = 0L
        private var bytes = 0L
        var current: String = ""
            set(value) {
                field = value
                publish(force = true)
            }

        override fun onBytes(count: Long) {
            bytes += count
            publish(force = false)
        }

        private fun publish(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < 250) return
            lastPublish = now
            _progress.value = _progress.value?.copy(doneBytes = bytes, currentName = current)
        }

        override val isCancelled: Boolean get() = cancelRequested
    }

    private fun launchJob(
        kind: TransferKind,
        subject: String,
        folder: String,
        total: () -> Long,
        block: suspend (Tracker) -> TransferOutcome,
    ): Boolean {
        if (isBusy) return false
        cancelRequested = false
        _progress.value = TransferProgress(kind, subject, folder, totalBytes = 0)
        job = scope.launch {
            // Pas de notification ni d'annonce pour une opération instantanée.
            val slow = launch {
                delay(800)
                TransferService.start(app)
                delay(700)
                _messages.emit(app.getString(startMessage(kind), subject, folder))
            }
            val outcome = try {
                val bytes = withContext(Dispatchers.IO) { runCatching(total).getOrDefault(0L) }
                _progress.value = _progress.value?.copy(totalBytes = bytes)
                withContext(Dispatchers.IO) { block(Tracker()) }
            } catch (e: TransferCancelledException) {
                TransferOutcome(app.getString(R.string.operation_cancelled), "", null)
            } catch (e: FileOperationException) {
                TransferOutcome(app.getString(e.reason.message(), subject), "", null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TransferOutcome(app.getString(R.string.op_failed, subject), "", null)
            } finally {
                slow.cancel()
                _conflict.value = null
            }
            lastOutcome = outcome
            _progress.value = null
            _messages.emit(outcome.message)
            _outcomes.emit(outcome)
        }
        return true
    }

    private fun startMessage(kind: TransferKind) = when (kind) {
        TransferKind.COPY -> R.string.started_copy
        TransferKind.MOVE -> R.string.started_move
        TransferKind.EXTRACT -> R.string.started_extract
        TransferKind.COMPRESS -> R.string.started_compress
    }

    private suspend fun askConflict(prompt: ConflictPrompt): ConflictAnswer {
        val deferred = CompletableDeferred<ConflictAnswer>()
        pendingConflict = deferred
        _conflict.value = prompt
        return deferred.await()
    }

    private suspend fun pasteItems(clip: ClipContent, dir: String, folder: String, tracker: Tracker): TransferOutcome {
        val mode = clip.mode
        val res = app.resources
        val conflicts = transfer.conflicts(clip.items.map { it.path }, dir)
        var remainingConflicts = conflicts.size
        var applyAll: ConflictChoice? = null
        var cancelled = false
        val done = mutableListOf<String>()
        var skipped = 0
        var alreadyHere = 0
        val failures = mutableListOf<FileOperationException.Reason>()

        for (item in clip.items) {
            if (cancelRequested) {
                cancelled = true
                break
            }
            tracker.current = item.name
            var choice: ConflictChoice? = null
            if (item.path in conflicts) {
                remainingConflicts--
                val chosen = applyAll ?: askConflict(ConflictPrompt(item.name, item.isDirectory, folder, remainingConflicts)).let { answer ->
                    if (answer.cancel) null else answer.choice.also { if (answer.applyToAll) applyAll = it }
                }
                if (chosen == null) {
                    cancelled = true
                    break
                }
                choice = chosen
            }
            try {
                when (val result = transfer.transfer(item.path, dir, mode, choice, tracker)) {
                    is TransferResult.Done -> {
                        done += result.path
                        if (mode == TransferMode.MOVE) preferences.onPathRenamed(item.path, result.path)
                    }
                    TransferResult.Skipped -> skipped++
                    TransferResult.AlreadyHere -> alreadyHere++
                }
            } catch (e: TransferCancelledException) {
                cancelled = true
                break
            } catch (e: FileOperationException) {
                failures += e.reason
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures += FileOperationException.Reason.FAILED
            }
        }

        // Après un déplacement, les éléments ne sont plus à leur ancienne place : presse-papiers vidé.
        if (mode == TransferMode.MOVE && !cancelled) clipboard.clear()

        val message = buildList {
            if (cancelled) add(app.getString(R.string.operation_cancelled))
            if (done.isNotEmpty()) {
                val plural = if (mode == TransferMode.COPY) R.plurals.pasted_copy else R.plurals.pasted_move
                add(res.getQuantityString(plural, done.size, done.size, File(done.first()).name, folder))
            }
            if (skipped > 0) add(res.getQuantityString(R.plurals.paste_skipped, skipped, skipped))
            if (alreadyHere > 0) add(res.getQuantityString(R.plurals.paste_already_here, alreadyHere, alreadyHere))
            if (failures.size == 1 && clip.items.size == 1) {
                add(app.getString(failures.first().message(), clip.items.first().name))
            } else if (failures.isNotEmpty()) {
                add(res.getQuantityString(R.plurals.op_failures, failures.size, failures.size))
            }
        }.joinToString(" ")
        return TransferOutcome(message, dir, done.firstOrNull())
    }
}
