package io.github.thomaszimmer13.xfiles.data.permission

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** Permission « Accès à tous les fichiers » (MANAGE_EXTERNAL_STORAGE, Android 11+). */
object StorageAccess {

    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    /**
     * Ouvre la page de réglage propre à XFiles ; si le fabricant ne la fournit pas,
     * ouvre la liste générale des apps ayant accès à tous les fichiers.
     * Renvoie false si aucune des deux pages n'existe.
     */
    fun openSettings(context: Context): Boolean {
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (intent in listOf(appSpecific, general)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // On essaie la page suivante.
            }
        }
        return false
    }
}
