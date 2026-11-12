package io.github.thomaszimmer13.xfiles

import android.app.Application
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
}
