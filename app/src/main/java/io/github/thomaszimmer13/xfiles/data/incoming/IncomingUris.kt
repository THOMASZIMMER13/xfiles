package io.github.thomaszimmer13.xfiles.data.incoming

import android.net.Uri

/**
 * Fichiers reçus d'une autre app (« Partager vers XFiles ») : seules les adresses content:// d'une
 * autre app sont acceptées (H-02). Refusés : file:// (XFiles y lirait ses propres fichiers privés) et
 * les adresses servies par XFiles lui-même (son FileProvider donnerait accès à son cache, coffres compris).
 */
object IncomingUris {
    fun acceptable(uri: Uri, packageName: String): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        return authority != packageName && !authority.startsWith("$packageName.")
    }
}
