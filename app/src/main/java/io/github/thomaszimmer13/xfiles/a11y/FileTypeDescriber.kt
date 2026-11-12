package io.github.thomaszimmer13.xfiles.a11y

import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * Type de fichier en clair, pour TalkBack : « document PDF », « image JPEG », « dossier »…
 * D'abord une table des extensions courantes, puis la famille MIME, puis « fichier XYZ ».
 */
object FileTypeDescriber {

    private val known: Map<String, String> = buildMap {
        put("pdf", "document PDF")
        putAll(listOf("doc", "docx").associateWith { "document Word" })
        put("odt", "document OpenDocument")
        put("rtf", "document RTF")
        putAll(listOf("xls", "xlsx").associateWith { "classeur Excel" })
        put("ods", "classeur OpenDocument")
        put("csv", "tableau CSV")
        putAll(listOf("ppt", "pptx").associateWith { "présentation PowerPoint" })
        put("odp", "présentation OpenDocument")
        put("txt", "texte")
        put("md", "texte Markdown")
        put("log", "journal")
        putAll(listOf("html", "htm").associateWith { "page web" })
        put("json", "fichier JSON")
        put("xml", "fichier XML")
        put("epub", "livre EPUB")
        put("zip", "archive ZIP")
        put("rar", "archive RAR")
        put("7z", "archive 7z")
        put("tar", "archive TAR")
        put("gz", "archive GZ")
        put("apk", "application Android")
        put("lua", "script Lua")
        put("sh", "script shell")
        put("py", "script Python")
        put("js", "script JavaScript")
        put("kt", "code Kotlin")
        put("vcf", "carte de contact")
        put("ics", "calendrier")
        put("srt", "sous-titres")
    }

    fun describe(name: String, isDirectory: Boolean): String {
        if (isDirectory) return "dossier"
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (ext.isEmpty() || ext == name.lowercase(Locale.ROOT)) return "fichier"
        known[ext]?.let { return it }

        val upper = ext.uppercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
        return when {
            mime.startsWith("image/") -> "image $upper"
            mime.startsWith("audio/") -> "audio $upper"
            mime.startsWith("video/") -> "vidéo $upper"
            mime.startsWith("text/") -> "texte $upper"
            else -> "fichier $upper"
        }
    }
}
