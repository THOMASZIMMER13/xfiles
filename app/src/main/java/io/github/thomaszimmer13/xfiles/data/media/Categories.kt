package io.github.thomaszimmer13.xfiles.data.media

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import io.github.thomaszimmer13.xfiles.data.trash.LocalTrash
import java.io.File

/** Catégories de la racine de l'Explorer, lues dans l'index des médias d'Android. */
enum class Category { IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APK }

object Categories {

    private val DOCUMENT_MIMES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/rtf",
        "application/epub+zip",
    )

    private val ARCHIVE_EXTENSIONS = listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    /** Clause SQL et arguments pour [category]. */
    private fun selection(category: Category): Pair<String, Array<String>> {
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        return when (category) {
            Category.IMAGES -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            Category.VIDEOS -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            Category.AUDIO -> "$type = ?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            Category.DOCUMENTS ->
                "($mime IN (${DOCUMENT_MIMES.joinToString(",") { "?" }}) OR $mime LIKE ?)" to (DOCUMENT_MIMES + "text/%").toTypedArray()
            Category.ARCHIVES ->
                ARCHIVE_EXTENSIONS.joinToString(" OR ", "(", ")") { "$name LIKE ?" } to ARCHIVE_EXTENSIONS.map { "%.$it" }.toTypedArray()
            Category.APK -> "$name LIKE ?" to arrayOf("%.apk")
        }
    }

    /** Chemins des fichiers de [category], les plus récents d'abord (au plus [limit]). */
    fun paths(context: Context, category: Category, limit: Int = 5000): List<String> {
        val (where, args) = selection(category)
        @Suppress("DEPRECATION")
        val data = MediaStore.Files.FileColumns.DATA
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val result = mutableListOf<String>()
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(data), query, null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (isVisible(path)) result += path
            }
        }
        return result
    }

    /** Listes de lecture (m3u, m3u8) connues de l'index des médias, les plus récentes d'abord. */
    fun playlistFiles(context: Context, limit: Int = 200): List<String> {
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        @Suppress("DEPRECATION")
        val data = MediaStore.Files.FileColumns.DATA
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "($name LIKE ? OR $name LIKE ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%.m3u", "%.m3u8"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val result = mutableListOf<String>()
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(data), query, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.takeIf(::isVisible)?.let(result::add)
        }
        return result
    }

    /** Nombre de fichiers de [category] (fichiers cachés et corbeille exclus). */
    fun count(context: Context, category: Category): Int = paths(context, category).size

    /** Dossiers cachés, données des apps et corbeille : jamais montrés dans les catégories. */
    fun isVisible(path: String): Boolean =
        path.split('/').none { it.startsWith(".") || io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer.isReplaceTemp(it) } &&
            "/Android/data/" !in path && "/Android/obb/" !in path && "/${LocalTrash.DIR_NAME}/" !in path

    /** Dossier Téléchargements de la mémoire interne. */
    fun downloadsDir(): File = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
}
