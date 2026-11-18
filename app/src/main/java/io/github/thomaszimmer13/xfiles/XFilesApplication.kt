package io.github.thomaszimmer13.xfiles

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.thomaszimmer13.xfiles.data.feedback.Feedback
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequests
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import io.github.thomaszimmer13.xfiles.data.source.FileSource
import io.github.thomaszimmer13.xfiles.data.source.LocalFileSource
import io.github.thomaszimmer13.xfiles.data.transfer.FileClipboard
import io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer
import io.github.thomaszimmer13.xfiles.data.transfer.TransferManager
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import io.github.thomaszimmer13.xfiles.data.volumes.VolumeRepository
import kotlinx.coroutines.launch

class XFilesApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Vrai quand une activité de l'app est visible (pour décider d'une notification de bilan). */
    @Volatile var isInForeground = false
        private set
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                isInForeground = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** Injection de dépendances manuelle : un seul endroit où les objets partagés sont créés. */
class AppContainer(application: Application) {
    val volumeRepository = VolumeRepository(application)
    val preferences = PreferencesRepository(application)

    /** Pour l'instant une seule source ; un routeur choisira plus tard selon le chemin (Shizuku…). */
    val fileSource: FileSource = LocalFileSource()

    /** Corbeille du stockage local, une par volume. */
    val trash = LocalTrash({ volumeRepository.volumeRoots().map { it.second } })

    /** Copier / Déplacer → Coller ici, partagé entre tous les dossiers et onglets. */
    val clipboard = FileClipboard()
    val transfer = LocalTransfer()

    /** Opérations longues (coller, extraire, compresser), qui survivent aux changements d'écran. */
    val feedback = Feedback(preferences)

    /** Nettoyage et analyse du stockage ; le résultat reste disponible tant que l'app vit. */
    val analyzer = io.github.thomaszimmer13.xfiles.data.analysis.StorageAnalyzer { volumeRepository.volumeRoots().map { it.second } }
    val transfers = TransferManager(application, preferences, clipboard, transfer, feedback)

    /** Lecteur audio et vidéo intégré. */
    val player = io.github.thomaszimmer13.xfiles.player.PlayerHub(application, preferences, feedback)

    /** Reconnaissance de texte (images, PDF scannés, scanner). */
    val ocr = io.github.thomaszimmer13.xfiles.data.ocr.OcrService(application, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))

    /** Serveurs réseau (SMB, SFTP, FTP, WebDAV) et leurs connexions. */
    val servers = io.github.thomaszimmer13.xfiles.data.remote.ServerStore(application)
    private val remoteScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Stockage en ligne (Google Drive, Dropbox, OneDrive) : connexion et jetons. */
    val cloud = io.github.thomaszimmer13.xfiles.data.remote.CloudAuth(
        application,
        io.github.thomaszimmer13.xfiles.data.remote.CloudOAuth(BuildConfig.DROPBOX_APP_KEY, BuildConfig.ONEDRIVE_CLIENT_ID, BuildConfig.OAUTH_SCHEME),
    ) { id, token ->
        // Jeton de renouvellement remplacé par le service : on garde le nouveau.
        remoteScope.launch { servers.get(id)?.let { servers.save(it.copy(password = token)) } }
    }
    val remote = io.github.thomaszimmer13.xfiles.data.remote.RemoteSessions(remoteScope, cloud)

    /** Éléments choisis pour le renommage par lot. */
    val pendingRename = io.github.thomaszimmer13.xfiles.ui.rename.PendingRename()

    /** Partage vers XFiles, choix d'un fichier pour une autre app, raccourci vers un dossier. */
    val incoming = IncomingRequests()
}
