package io.github.thomaszimmer13.xfiles.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.thomaszimmer13.xfiles.MainActivity
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.XFilesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Service de premier plan pendant une opération longue : Android ne l'interrompt pas quand on quitte l'app.
 * La notification montre la progression et propose « Annuler » ; à la fin, si l'app n'est pas à l'écran,
 * une notification de bilan la remplace (« 3 éléments copiés dans Documents »).
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager by lazy { (application as XFilesApplication).container.transfers }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        startForeground(PROGRESS_ID, progressNotification(manager.progress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            // Une mise à jour par seconde au plus : la notification n'a pas besoin de plus.
            manager.progress.sample(1000).collect { progress ->
                if (progress == null) finish() else notify(PROGRESS_ID, progressNotification(progress))
            }
        }
        scope.launch {
            manager.progress.collect { if (it == null) finish() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) manager.cancel()
        if (manager.progress.value == null) finish()
        return START_NOT_STICKY
    }

    /** Android 15 : durée maximale atteinte pour ce type de service. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.cancel()
    }

    private var finished = false

    private fun finish() {
        if (finished) return
        finished = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        val outcome = manager.lastOutcome
        if (outcome != null && !(application as XFilesApplication).isInForeground) {
            notify(DONE_ID, doneNotification(outcome.message))
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(id: Int, notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(progress: TransferProgress?): Notification {
        val title = progress?.let { getString(titleFor(it.kind), it.subject, it.folder) } ?: getString(R.string.app_name)
        val text = progress?.let { p ->
            listOf(p.currentName, getString(R.string.progress_percent, p.percent)).filter { it.isNotEmpty() }.joinToString(" · ")
        }.orEmpty()
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copy)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress?.percent ?: 0, progress == null || progress.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.action_cancel), cancel)
            .build()
    }

    private fun doneNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_copy)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        private const val CHANNEL_ID = "transferts"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "io.github.thomaszimmer13.xfiles.ANNULER"

        fun titleFor(kind: TransferKind) = when (kind) {
            TransferKind.COPY -> R.string.started_copy
            TransferKind.MOVE -> R.string.started_move
            TransferKind.EXTRACT -> R.string.started_extract
            TransferKind.COMPRESS -> R.string.started_compress
            TransferKind.IMPORT -> R.string.started_import
        }

        /** Démarre le service ; refusé par Android si l'app vient de passer en arrière-plan : on l'ignore. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_transfers),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
