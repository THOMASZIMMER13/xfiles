package io.github.thomaszimmer13.xfiles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import io.github.thomaszimmer13.xfiles.data.incoming.IncomingRequest
import io.github.thomaszimmer13.xfiles.data.open.Shortcuts
import io.github.thomaszimmer13.xfiles.ui.XFilesApp
import io.github.thomaszimmer13.xfiles.ui.theme.XFilesTheme

class MainActivity : ComponentActivity() {
    /** Vrai si cette fenêtre a reçu un partage ou une demande de fichier : à oublier quand elle se ferme. */
    private var ownsRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Après une rotation, la demande en cours est déjà connue : on ne la relit pas.
        if (savedInstanceState == null) handle(intent) else ownsRequest = savedInstanceState.getBoolean(KEY_OWNS, false)
        setContent {
            XFilesTheme {
                XFilesApp()
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
