package io.github.thomaszimmer13.xfiles.ui.storage

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.a11y.DateFormatter
import io.github.thomaszimmer13.xfiles.a11y.SizeFormatter
import io.github.thomaszimmer13.xfiles.data.analysis.AnalysisState
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import io.github.thomaszimmer13.xfiles.ui.components.FocusOnceEffect
import io.github.thomaszimmer13.xfiles.ui.components.ItemRow
import io.github.thomaszimmer13.xfiles.ui.components.SectionHeader
import io.github.thomaszimmer13.xfiles.ui.components.StatusMessage
import io.github.thomaszimmer13.xfiles.ui.components.XFilesTopBar
import io.github.thomaszimmer13.xfiles.ui.components.positioned
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val analyzer = (application as XFilesApplication).container.analyzer
    val state = analyzer.state
    val announcer = Announcer(viewModelScope)

    init {
        // Annonces : début et fin de l'analyse seulement (pas de compteur lu en continu).
        viewModelScope.launch {
            analyzer.state.distinctUntilChangedBy { it.running }.drop(1).collect { s ->
                val app = getApplication<Application>()
                val result = s.result
                if (!s.running && result != null) {
                    announcer.announce(app.resources.getQuantityString(R.plurals.storage_done, result.scannedFiles, result.scannedFiles, SizeFormatter.spoken(result.scannedBytes)))
                }
            }
        }
    }

    fun start() {
        analyzer.start()
        announcer.announce(getApplication<Application>().getString(R.string.storage_started))
    }

    fun cancel() {
        analyzer.cancel()
        announcer.announce(getApplication<Application>().getString(R.string.storage_cancelled))
    }
}

/**
 * « Nettoyage et analyse ». Ordre de balayage : Retour → titre → état ou bouton Analyser →
 * « À nettoyer » (gros fichiers, doublons, anciens téléchargements) → « Dossiers les plus lourds ».
 * Chaque catégorie de nettoyage ouvre une liste à cocher.
 */
@Composable
fun StorageScreen(onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: StorageViewModel = viewModel()) {
    val state: AnalysisState by viewModel.state.collectAsStateWithLifecycle()
    val announcement by viewModel.announcer.message.collectAsStateWithLifecycle()
    val titleFocus = remember { FocusRequester() }
    FocusOnceEffect(ready = true) { titleFocus.requestFocus() }
    // Première visite : l'analyse démarre d'elle-même.
    LaunchedEffect(Unit) { if (state.result == null && !state.running) viewModel.start() }

    val result = state.result
    val openLabel = stringResource(R.string.action_open)

    Column(Modifier.fillMaxSize()) {
        XFilesTopBar(title = stringResource(R.string.storage_title), onBack = onBack, titleFocus = titleFocus)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "status") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val text = when {
                        state.running && state.comparing -> pluralStringResource(R.plurals.storage_comparing, state.scanned, state.scanned)
                        state.running -> pluralStringResource(R.plurals.storage_scanning, state.scanned, state.scanned)
                        result != null -> pluralStringResource(
                            R.plurals.storage_summary,
                            result.scannedFiles,
                            result.scannedFiles,
                            SizeFormatter.short(result.scannedBytes),
                            DateFormatter.spoken(result.finishedAt, verb = "analysé"),
                        )
                        else -> stringResource(R.string.storage_intro)
                    }
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    if (state.running) {
                        OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.action_stop_analysis))
                        }
                    } else {
                        Button(onClick = viewModel::start, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(if (result == null) R.string.action_analyze else R.string.action_analyze_again))
                        }
                    }
                }
            }
            if (result != null && !state.running) {
                item(key = "h-clean") { SectionHeader(stringResource(R.string.storage_section_clean)) }
                item(key = "big") {
                    val n = result.bigFiles.size
                    val size = result.bigFiles.sumOf { it.size }
                    CleanupEntry(
                        title = stringResource(R.string.cleanup_big),
                        detail = pluralStringResource(R.plurals.cleanup_files, n, n, SizeFormatter.short(size)),
                        spokenDetail = pluralStringResource(R.plurals.cleanup_files, n, n, SizeFormatter.spoken(size)),
                        hint = stringResource(R.string.cleanup_big_hint),
                        openLabel = openLabel,
                        onOpen = { onOpen(Routes.cleanup(CleanupKind.BIG)) },
                    )
                }
                item(key = "dup") {
                    val n = result.duplicates.size
                    val wasted = result.duplicates.sumOf { it.wasted }
                    CleanupEntry(
                        title = stringResource(R.string.cleanup_duplicates),
                        detail = pluralStringResource(R.plurals.cleanup_groups, n, n, SizeFormatter.short(wasted)),
                        spokenDetail = pluralStringResource(R.plurals.cleanup_groups, n, n, SizeFormatter.spoken(wasted)),
                        hint = stringResource(R.string.cleanup_duplicates_hint),
                        openLabel = openLabel,
                        onOpen = { onOpen(Routes.cleanup(CleanupKind.DUPLICATES)) },
                    )
                }
                item(key = "old") {
                    val n = result.oldDownloads.size
                    val size = result.oldDownloads.sumOf { it.size }
                    CleanupEntry(
                        title = stringResource(R.string.cleanup_downloads),
                        detail = pluralStringResource(R.plurals.cleanup_files, n, n, SizeFormatter.short(size)),
                        spokenDetail = pluralStringResource(R.plurals.cleanup_files, n, n, SizeFormatter.spoken(size)),
                        hint = stringResource(R.string.cleanup_downloads_hint),
                        openLabel = openLabel,
                        onOpen = { onOpen(Routes.cleanup(CleanupKind.OLD_DOWNLOADS)) },
                    )
                }
                if (result.heaviestFolders.isNotEmpty()) {
                    item(key = "h-folders") { SectionHeader(stringResource(R.string.storage_section_folders)) }
                    itemsIndexed(result.heaviestFolders, key = { _, it -> "f:" + it.path }) { index, folder ->
                        val files = pluralStringResource(R.plurals.files_count, folder.files, folder.files)
                        ItemRow(
                            icon = R.drawable.ic_tab_explorer,
                            title = folder.name,
                            detail = "${SizeFormatter.short(folder.bytes)} · $files",
                            spoken = positioned("${folder.name}, ${SizeFormatter.spoken(folder.bytes)}, $files", index, result.heaviestFolders.size),
                            clickLabel = openLabel,
                            onClick = { onOpen(folder.path) },
                        )
                    }
                }
            }
        }
        StatusMessage(announcement)
    }
}

/** Ligne « Gros fichiers, 12 fichiers, 3,4 gigaoctets. 50 mégaoctets ou plus. » qui ouvre la liste à cocher. */
@Composable
private fun CleanupEntry(title: String, detail: String, spokenDetail: String, hint: String, openLabel: String, onOpen: () -> Unit) {
    ItemRow(
        icon = R.drawable.ic_delete,
        title = title,
        detail = "$detail · $hint",
        spoken = "$title, $spokenDetail. $hint",
        clickLabel = openLabel,
        onClick = onOpen,
    )
}
