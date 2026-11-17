package io.github.thomaszimmer13.xfiles.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import io.github.thomaszimmer13.xfiles.data.archive.isZip
import io.github.thomaszimmer13.xfiles.data.open.FileOpener
import io.github.thomaszimmer13.xfiles.ui.browser.Routes
import kotlinx.coroutines.launch

/**
 * Ouvrir un élément, partout dans l'app, de la même façon :
 * dossier → on y entre ; archive zip → on la parcourt dans XFiles ; autre fichier → app par défaut.
 * Chaque fichier ouvert est ajouté à l'onglet Récents.
 */
class FileLauncher internal constructor(
    private val context: Context,
    private val onRoute: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val record: (String) -> Unit,
    private val texts: Texts,
) {
    internal class Texts(val openWith: String, val share: String, val noApp: String, val failed: String)

    fun open(path: String, name: String, isDirectory: Boolean) {
        when {
            isDirectory -> onRoute(path)
            isZip(name) -> {
                record(path)
                onRoute(Routes.zip(path))
            }
            else -> {
                record(path)
                report(FileOpener.open(context, path, name))
            }
        }
    }

    fun openWith(path: String, name: String) {
        record(path)
        report(FileOpener.open(context, path, name, chooserTitle = texts.openWith))
    }

    fun share(path: String, name: String) = report(FileOpener.share(context, path, name, texts.share))

    fun shareMany(files: List<Pair<String, String>>) = report(FileOpener.shareMany(context, files, texts.share))

    /** Ouvre un fichier temporaire (aperçu d'un élément d'archive) sans l'ajouter aux récents. */
    fun openTemporary(path: String, name: String, chooser: Boolean) =
        report(FileOpener.open(context, path, name, chooserTitle = if (chooser) texts.openWith else null))

    private fun report(result: FileOpener.Result) {
        when (result) {
            FileOpener.Result.OK -> Unit
            FileOpener.Result.NO_APP -> onProblem(texts.noApp)
            FileOpener.Result.FAILED -> onProblem(texts.failed)
        }
    }
}

/**
 * @param onRoute ouvre un dossier ou un écran (archive) dans la pile de l'onglet courant.
 * @param onProblem annonce un problème (« Aucune application ne sait ouvrir ce fichier »).
 */
@Composable
fun rememberFileLauncher(onRoute: (String) -> Unit, onProblem: (String) -> Unit): FileLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route by rememberUpdatedState(onRoute)
    val problem by rememberUpdatedState(onProblem)
    val texts = FileLauncher.Texts(
        openWith = stringResource(R.string.chooser_open_with),
        share = stringResource(R.string.chooser_share),
        noApp = stringResource(R.string.open_no_app),
        failed = stringResource(R.string.open_failed),
    )
    return remember(context, texts.openWith) {
        val preferences = (context.applicationContext as XFilesApplication).container.preferences
        FileLauncher(
            context = context,
            onRoute = { route(it) },
            onProblem = { problem(it) },
            record = { path -> scope.launch { preferences.recordOpened(path) } },
            texts = texts,
        )
    }
}

/**
 * Demande l'autorisation d'afficher des notifications (Android 13 et plus) juste avant une opération longue,
 * pour que sa progression apparaisse. L'opération démarre quelle que soit la réponse.
 */
@Composable
fun rememberNotificationPermission(): (then: () -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending[0]?.invoke()
        pending[0] = null
    }
    return remember(launcher) {
        { then ->
            val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needed) {
                pending[0] = then
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                then()
            }
        }
    }
}
