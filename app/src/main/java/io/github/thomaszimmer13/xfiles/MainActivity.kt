package io.github.thomaszimmer13.xfiles

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.util.Rational
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.provider.OpenableColumns
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequest
import io.github.thomaszimmer13.xfiles.data.open.Shortcuts
import io.github.thomaszimmer13.xfiles.player.PlaybackService
import io.github.thomaszimmer13.xfiles.ui.XFilesApp
import io.github.thomaszimmer13.xfiles.ui.security.LockScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thomaszimmer13.xfiles.ui.theme.XFilesTheme

/** FragmentActivity : nécessaire à la fenêtre d'identification d'Android (verrouillage, coffres). */
class MainActivity : FragmentActivity() {
    /** Vrai si cette fenêtre a reçu un partage ou une demande de fichier : à oublier quand elle se ferme. */
    private var ownsRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Après une rotation, la demande en cours est déjà connue : on ne la relit pas.
        if (savedInstanceState == null) handle(intent) else ownsRequest = savedInstanceState.getBoolean(KEY_OWNS, false)
        // Android 12 et plus : passage automatique en image dans l'image pendant une vidéo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hub = (application as XFilesApplication).container.player
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(hub.state, hub.screenOpen) { s, open -> s.isVideo && s.isPlaying && open }
                        .distinctUntilChanged()
                        .collect { runCatching { setPictureInPictureParams(pipParams()) } }
                }
            }
        }
        val lock = (application as XFilesApplication).container.appLock
        // Verrouillage actif ou coffre ouvert : pas d'aperçu du contenu dans l'écran des applications
        // récentes (audit M-04).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val vaultKeys = (application as XFilesApplication).container.vaultKeys
            lifecycleScope.launch {
                combine(lock.enabled, vaultKeys.open) { locked, open -> locked || open.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { hide -> setRecentsScreenshotEnabled(!hide) }
            }
        }
        setContent {
            XFilesTheme {
                val locked by lock.locked.collectAsStateWithLifecycle()
                // L'état de navigation est gardé pendant que l'écran de verrouillage le remplace.
                val holder = rememberSaveableStateHolder()
                if (locked) {
                    LockScreen(onUnlocked = lock::unlocked, onDisable = { lock.setEnabled(false); lock.unlocked() })
                } else {
                    holder.SaveableStateProvider("app") { XFilesApp() }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OWNS, ownsRequest)
    }

    override fun onDestroy() {
        // Fermée sans avoir choisi : l'Explorer ne doit pas rester en mode « choisir un fichier ».
        if (isFinishing && ownsRequest) (application as XFilesApplication).container.incoming.clear()
        super.onDestroy()
    }

    /** Une vidéo est-elle en lecture, lecteur ouvert ? (image dans l'image possible) */
    private fun videoPlaying(): Boolean {
        val hub = (application as XFilesApplication).container.player
        val state = hub.state.value
        return state.isVideo && state.isPlaying && hub.screenOpen.value
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun pipParams(): PictureInPictureParams {
        val size = (application as XFilesApplication).container.player.player?.videoSize
        return PictureInPictureParams.Builder().apply {
            if (size != null && size.width > 0 && size.height > 0) {
                val ratio = Rational((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
                // Android refuse les formats trop étirés.
                if (ratio.toFloat() in 0.42f..2.39f) setAspectRatio(ratio)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(videoPlaying())
        }.build()
    }

    /** On quitte l'app pendant une vidéo : elle continue en image dans l'image (Android 11). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && videoPlaying()) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        (application as XFilesApplication).container.player.setPictureInPicture(isInPictureInPictureMode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Traduit ce que demande l'intent (partage, choix d'un fichier, raccourci) pour l'interface. */
    private fun handle(intent: Intent?) {
        val incoming = (application as XFilesApplication).container.incoming
        val request = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { save(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { save(it) }
            Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK -> IncomingRequest.Pick(
                listOfNotNull(intent.type) + intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES).orEmpty(),
            )
            Shortcuts.ACTION_OPEN_FOLDER -> intent.getStringExtra(Shortcuts.EXTRA_PATH)?.let { IncomingRequest.OpenFolder(it) }
            PlaybackService.ACTION_OPEN_PLAYER -> {
                (application as XFilesApplication).container.player.openScreen()
                null
            }
            else -> null
        }
        if (request != null) {
            incoming.set(request)
            ownsRequest = request !is IncomingRequest.OpenFolder
        }
    }

    private fun save(uris: List<Uri>): IncomingRequest.Save {
        val names = uris.map { uri ->
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        }
        return IncomingRequest.Save(uris, names)
    }

    private companion object {
        const val KEY_OWNS = "owns_request"
    }
}
