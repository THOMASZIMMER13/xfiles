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

    /** Partage vers XFiles, choix d'un fichier pour une autre app, raccourci vers un dossier. */
    val incoming = IncomingRequests()
}
