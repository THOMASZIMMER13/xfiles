package io.github.thomaszimmer13.xfiles.data.open

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** Transmission d'un fichier aux autres apps : ouvrir, ouvrir avec, partager. */
object FileOpener {

    enum class Result { OK, NO_APP, FAILED }

    /** Type MIME d'après l'extension ; « * / * » si inconnu. */
    fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /**
     * Ouvre le fichier dans l'app par défaut (ou le sélecteur d'Android s'il n'y en a pas).
     * @param chooserTitle si non nul, force le choix de l'app (« Ouvrir avec »).
     */
    fun open(context: Context, path: String, name: String, chooserTitle: String? = null): Result {
        val uri = uriFor(context, path) ?: return Result.FAILED
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return start(context, if (chooserTitle != null) Intent.createChooser(view, chooserTitle) else view)
    }

    fun share(context: Context, path: String, name: String, chooserTitle: String): Result {
        val uri = uriFor(context, path) ?: return Result.FAILED
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData : nécessaire pour que l'autorisation de lecture suive jusqu'à l'app choisie.
        send.clipData = ClipData.newRawUri(name, uri)
        return start(context, Intent.createChooser(send, chooserTitle))
    }

    /** Partage plusieurs fichiers d'un coup (ACTION_SEND_MULTIPLE). [files] : couples (chemin, nom). */
    fun shareMany(context: Context, files: List<Pair<String, String>>, chooserTitle: String): Result {
        if (files.size == 1) return share(context, files[0].first, files[0].second, chooserTitle)
        val uris = files.map { (path, _) -> uriFor(context, path) ?: return Result.FAILED }
        val types = files.map { (_, name) -> mimeType(name) }.distinct()
        // Type commun : « image/jpeg » si tous identiques, « image/* » si même famille, sinon « */* ».
        val type = when {
            types.size == 1 -> types[0]
            types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
            else -> "*/*"
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(type)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(files[0].second, uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        return start(context, Intent.createChooser(send, chooserTitle))
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun uriFor(context: Context, path: String): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun start(context: Context, intent: Intent): Result = try {
        context.startActivity(intent)
        Result.OK
    } catch (_: ActivityNotFoundException) {
        Result.NO_APP
    } catch (_: SecurityException) {
        Result.FAILED
    }
}
