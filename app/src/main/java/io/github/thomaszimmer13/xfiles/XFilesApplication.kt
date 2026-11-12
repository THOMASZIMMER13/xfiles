package io.github.thomaszimmer13.xfiles

import android.app.Application
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import io.github.thomaszimmer13.xfiles.data.source.FileSource
import io.github.thomaszimmer13.xfiles.data.source.LocalFileSource
import io.github.thomaszimmer13.xfiles.data.volumes.VolumeRepository

class XFilesApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Injection de dépendances manuelle : un seul endroit où les objets partagés sont créés. */
class AppContainer(application: Application) {
    val volumeRepository = VolumeRepository(application)
    val preferences = PreferencesRepository(application)

    /** Pour l'instant une seule source ; un routeur choisira plus tard selon le chemin (Shizuku…). */
    val fileSource: FileSource = LocalFileSource()
}
