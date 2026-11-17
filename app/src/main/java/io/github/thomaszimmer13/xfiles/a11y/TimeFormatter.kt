package io.github.thomaszimmer13.xfiles.a11y

/** Durées du lecteur : courte à l'écran (« 1:23 », « 1:02:05 »), en toutes lettres pour TalkBack. */
object TimeFormatter {

    fun short(ms: Long): String {
        if (ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** « 1 heure 2 minutes », « 4 minutes 10 secondes », « 45 secondes ». */
    fun spoken(ms: Long): String {
        if (ms < 0) return "durée inconnue"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = buildList {
            if (h > 0) add(unit(h, "heure"))
            if (m > 0) add(unit(m, "minute"))
            // Au-delà d'une heure, les secondes n'apportent rien à l'oreille.
            if (s > 0 && h == 0L) add(unit(s, "seconde"))
        }
        return parts.joinToString(" ").ifEmpty { "0 seconde" }
    }

    private fun unit(n: Long, word: String) = if (n > 1) "$n ${word}s" else "$n $word"

    /** « 1:23 », « 83 », « 1:02:05 » → millisecondes ; null si illisible. */
    fun parse(text: String): Long? {
        val parts = text.trim().replace('.', ':').replace(',', ':').split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        val seconds = numbers.fold(0L) { acc, n -> acc * 60 + n }
        return seconds * 1000
    }
}
