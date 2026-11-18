package io.github.thomaszimmer13.xfiles.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import io.github.thomaszimmer13.xfiles.BuildConfig
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Où en est Shizuku, dans l'ordre des étapes à faire. */
enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NO_PERMISSION, DENIED, READY }

/**
 * Accès à Android/data et Android/obb par Shizuku : état annoncé à l'utilisateur, demande
 * d'autorisation, puis service de fichiers ([FileService]) lancé avec les droits de Shizuku.
 */
class ShizukuAccess(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile private var service: IFileService? = null
    @Volatile private var latch = CountDownLatch(1)
    @Volatile private var bound = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, FileService::class.java.name))
            .daemon(false)
            .processNameSuffix("fichiers")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IFileService.Stub.asInterface(binder)
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            latch = CountDownLatch(1)
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            bound = false
            latch = CountDownLatch(1)
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _state.value = runCatching {
            when {
                !installed() -> ShizukuState.NOT_INSTALLED
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> ShizukuState.TOO_OLD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.NO_PERMISSION
            }
        }.getOrDefault(if (installed()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED)
    }

    private fun installed(): Boolean = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Fenêtre de Shizuku : « Autoriser XFiles ? ». */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun launchIntent(): Intent? = app.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntents(): List<Intent> = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** Service de fichiers, lancé au besoin (appel bloquant, hors du fil principal). */
    fun files(): IFileService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        refresh()
        if (_state.value != ShizukuState.READY) throw RemoteException(RemoteException.Reason.AUTH, "shizuku ${_state.value}")
        synchronized(this) {
            if (!bound) {
                latch = CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { Shizuku.bindUserService(args, connection) }.onFailure { latch.countDown() }
                }
                bound = true
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            bound = false
            throw RemoteException(RemoteException.Reason.UNREACHABLE, "shizuku")
        }
        return service ?: run {
            bound = false
            throw RemoteException(RemoteException.Reason.UNREACHABLE, "shizuku")
        }
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242

        /** Identifiant du « serveur » virtuel des données des applications. */
        const val SERVER_ID = "shizuku"

        /** Racine montrée : dossier Android du stockage interne (data, obb, media). */
        const val ROOT = "/storage/emulated/0/Android"
    }
}
