package io.github.thomaszimmer13.xfiles.data.open

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.thomaszimmer13.xfiles.MainActivity
import io.github.thomaszimmer13.xfiles.R

/** Raccourcis de l'écran d'accueil vers un dossier. */
object Shortcuts {
    const val ACTION_OPEN_FOLDER = "io.github.thomaszimmer13.xfiles.OPEN_FOLDER"
    const val EXTRA_PATH = "path"

    /**
     * Demande au lanceur d'ajouter un raccourci vers [path] : Android affiche sa propre confirmation.
     * @return false si le lanceur ne sait pas ajouter de raccourci.
     */
    fun pinFolder(context: Context, path: String, label: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_FOLDER)
            .putExtra(EXTRA_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val shortcut = ShortcutInfoCompat.Builder(context, "dossier:$path")
            .setShortLabel(label.take(24).ifEmpty { label })
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    }
}
