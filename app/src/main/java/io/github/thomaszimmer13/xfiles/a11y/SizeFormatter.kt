package io.github.thomaszimmer13.xfiles.a11y

import java.text.NumberFormat
import java.util.Locale

/**
 * Tailles de fichiers en deux versions :
 * - [short] pour l'affichage : « 34,2 Go » ;
 * - [spoken] pour TalkBack, unités en toutes lettres : « 34,2 gigaoctets ».
 *
 * Base 1000, comme les Paramètres d'Android, pour que les chiffres concordent.
 */
object SizeFormatter {

    private data class Unit(val factor: Double, val short: String, val singular: String, val plural: String)

    private val units = listOf(
        Unit(1.0, "o", "octet", "octets"),
        Unit(1e3, "ko", "kilooctet", "kilooctets"),
        Unit(1e6, "Mo", "mégaoctet", "mégaoctets"),
        Unit(1e9, "Go", "gigaoctet", "gigaoctets"),
        Unit(1e12, "To", "téraoctet", "téraoctets"),
    )

    private val locale = Locale.FRANCE

    fun short(bytes: Long): String {
        val (value, unit) = scale(bytes)
        return "${format(value)} ${unit.short}"
    }

    fun spoken(bytes: Long): String {
        val (value, unit) = scale(bytes)
        // En français, le singulier s'emploie en dessous de 2 (« 1,5 gigaoctet »).
        val word = if (value < 2) unit.singular else unit.plural
        return "${format(value)} $word"
    }

    private fun scale(bytes: Long): Pair<Double, Unit> {
        val positive = bytes.coerceAtLeast(0)
        val unit = units.lastOrNull { positive >= it.factor } ?: units.first()
        return positive / unit.factor to unit
    }

    private fun format(value: Double): String {
        val nf = NumberFormat.getNumberInstance(locale)
        // Une décimale sous 100 (« 34,2 »), aucune au-delà (« 128 »).
        nf.maximumFractionDigits = if (value < 100) 1 else 0
        nf.minimumFractionDigits = 0
        return nf.format(value)
    }
}
