package io.github.thomaszimmer13.xfiles.data.media

import java.io.File

/** Types de fichiers lus par le lecteur intégré, d'après l'extension. */
object MediaKinds {
    private val AUDIO = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mid", "midi",
        // Grâce au décodeur FFmpeg :
        "wma", "ac3", "eac3", "dts", "mka", "alac", "ape", "aiff", "aif",
    )
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "ts", "mov", "avi", "wmv", "mpg", "mpeg")
    private val PLAYLIST = setOf("m3u", "m3u8", "pls")
    private val SUBTITLE = listOf("srt", "vtt", "ass", "ssa")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isAudio(name: String) = ext(name) in AUDIO
    fun isVideo(name: String) = ext(name) in VIDEO
    fun isMedia(name: String) = isAudio(name) || isVideo(name)
    fun isPlaylist(name: String) = ext(name) in PLAYLIST

    /** Sous-titres posés à côté de la vidéo : « Film.srt », « Film.fr.srt »… */
    fun sidecarSubtitles(video: File): List<File> {
        val base = video.name.substringBeforeLast('.')
        return video.parentFile?.listFiles().orEmpty()
            .filter { f -> f.isFile && ext(f.name) in SUBTITLE && f.name.startsWith("$base.") }
            .sortedBy { it.name }
    }

    /** Langue lue dans le nom « Film.fr.srt » → « fr », sinon null. */
    fun subtitleLanguage(video: File, subtitle: File): String? {
        val rest = subtitle.name.removePrefix(video.name.substringBeforeLast('.') + ".")
        if (!rest.contains('.')) return null
        val middle = rest.substringBeforeLast('.')
        return middle.takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isLetter) }
    }
}

/** Élément d'une liste de lecture : fichier local ou adresse web. */
data class PlaylistEntry(
    /** Chemin absolu d'un fichier, ou adresse « http(s)://… ». */
    val location: String,
    /** Titre lu dans « #EXTINF » ou « TitleN= », s'il y en a un. */
    val title: String? = null,
    /** Durée en secondes indiquée par la liste ; -1 si inconnue. */
    val durationSeconds: Long = -1,
) {
    val isRemote: Boolean get() = location.startsWith("http://", true) || location.startsWith("https://", true)
}

/** Résultat de la lecture d'un fichier de liste. */
sealed interface ParsedPlaylist {
    /** Liste ordinaire : [missing] fichiers locaux introuvables ont été écartés. */
    data class Entries(val entries: List<PlaylistEntry>, val missing: Int) : ParsedPlaylist

    /** Le fichier est lui-même un flux HLS (radio, télé) : on le lit tel quel. */
    data object Stream : ParsedPlaylist
}

/** Lecture et écriture des listes m3u, m3u8 et pls. */
object Playlists {

    fun parse(file: File, exists: (File) -> Boolean = File::isFile): ParsedPlaylist {
        val text = file.readBytes().let { bytes ->
            // .m3u8 est toujours en UTF-8 ; un vieux .m3u peut être en Windows-1252.
            val utf8 = String(bytes, Charsets.UTF_8)
            if (file.extension.equals("m3u", true) && '�' in utf8) String(bytes, charset("windows-1252")) else utf8
        }.removePrefix("\uFEFF")
        val base = file.parentFile ?: File(".")
        return if (file.extension.equals("pls", true)) parsePls(text, base, exists) else parseM3u(text, base, exists)
    }

    private fun parseM3u(text: String, base: File, exists: (File) -> Boolean): ParsedPlaylist {
        val lines = text.lines().map { it.trim() }
        // Balises propres au HLS : c'est un flux, pas une liste de fichiers.
        if (lines.any { it.startsWith("#EXT-X-TARGETDURATION") || it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-MEDIA-SEQUENCE") }) {
            return ParsedPlaylist.Stream
        }
        val entries = mutableListOf<PlaylistEntry>()
        var missing = 0
        var title: String? = null
        var duration = -1L
        for (line in lines) {
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val info = line.substringAfter(':')
                    duration = info.substringBefore(',').trim().substringBefore(' ').toDoubleOrNull()?.toLong() ?: -1
                    title = info.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    when (val entry = resolve(line, base, title, duration, exists)) {
                        null -> missing++
                        else -> entries += entry
                    }
                    title = null
                    duration = -1
                }
            }
        }
        return ParsedPlaylist.Entries(entries, missing)
    }

    private fun parsePls(text: String, base: File, exists: (File) -> Boolean): ParsedPlaylist {
        val values = text.lines().mapNotNull { line ->
            val key = line.substringBefore('=', "").trim()
            if (key.isEmpty()) null else key.lowercase() to line.substringAfter('=').trim()
        }.toMap()
        val numbers = values.keys.filter { it.startsWith("file") }.mapNotNull { it.removePrefix("file").toIntOrNull() }.sorted()
        val entries = mutableListOf<PlaylistEntry>()
        var missing = 0
        for (n in numbers) {
            val title = values["title$n"]?.ifEmpty { null }
            val duration = values["length$n"]?.toLongOrNull() ?: -1
            when (val entry = resolve(values.getValue("file$n"), base, title, duration, exists)) {
                null -> missing++
                else -> entries += entry
            }
        }
        return ParsedPlaylist.Entries(entries, missing)
    }

    private fun resolve(raw: String, base: File, title: String?, duration: Long, exists: (File) -> Boolean): PlaylistEntry? {
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return PlaylistEntry(raw, title, duration)
        val cleaned = raw.removePrefix("file://").replace('\\', '/')
        val file = File(cleaned).let { if (it.isAbsolute) it else File(base, cleaned) }
        val normalized = runCatching { file.canonicalFile }.getOrDefault(file)
        return if (exists(normalized)) PlaylistEntry(normalized.path, title, duration) else null
    }

    /**
     * Texte d'un fichier m3u8 : chemins relatifs au dossier de la liste quand c'est possible,
     * pour qu'elle reste lisible ailleurs (VLC, PC, NAS).
     */
    fun toM3u(entries: List<PlaylistEntry>, playlistDir: File): String = buildString {
        append("#EXTM3U\n")
        for (e in entries) {
            val title = e.title ?: if (e.isRemote) e.location else File(e.location).nameWithoutExtension
            append("#EXTINF:").append(e.durationSeconds).append(',').append(title).append('\n')
            val location = if (e.isRemote) {
                e.location
            } else {
                runCatching { File(e.location).relativeTo(playlistDir).invariantSeparatorsPath }.getOrDefault(e.location)
            }
            append(location).append('\n')
        }
    }

    /** Ajoute [entries] à la fin d'une liste existante (ou la crée). */
    fun append(playlist: File, entries: List<PlaylistEntry>) {
        val current = if (playlist.isFile) (parse(playlist) as? ParsedPlaylist.Entries)?.entries.orEmpty() else emptyList()
        playlist.writeText(toM3u(current + entries, playlist.parentFile ?: File(".")))
    }
}
