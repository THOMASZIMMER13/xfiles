package io.github.thomaszimmer13.xfiles.data.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Remet les lignes reconnues dans l'ordre de lecture et les regroupe en paragraphes. */
object OcrLayout {

    private val END_MARKS = setOf('.', '!', '?', ':', '»')

    private class Placed(val line: OcrLine, val x: Float, val y: Float, val left: Float, val right: Float, val height: Float)

    fun paragraphs(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        // Repère du texte : direction moyenne des lignes (pondérée par leur longueur).
        val sx = lines.sumOf { (it.box.ux * it.box.width).toDouble() }
        val sy = lines.sumOf { (it.box.uy * it.box.width).toDouble() }
        val angle = atan2(sy, sx)
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val placed = lines.map {
            val x = it.box.cx * ux + it.box.cy * uy
            val y = -it.box.cx * uy + it.box.cy * ux
            Placed(it, x, y, x - it.box.width / 2, x + it.box.width / 2, it.box.height)
        }.sortedBy { it.y }

        // Rangées : lignes à la même hauteur (morceaux d'une même ligne, cellules de tableau).
        val rows = ArrayList<MutableList<Placed>>()
        for (p in placed) {
            val row = rows.lastOrNull()
            val rowY = row?.map { it.y }?.average()?.toFloat()
            val rowH = row?.maxOf { it.height }
            if (row != null && rowY != null && rowH != null && abs(p.y - rowY) < 0.5f * minOf(rowH, p.height)) row += p else rows += mutableListOf(p)
        }
        val texts = rows.map { row ->
            val sorted = row.sortedBy { it.x }
            buildString {
                sorted.forEachIndexed { i, p ->
                    if (i > 0) {
                        val gap = p.left - sorted[i - 1].right
                        append(if (gap > 2 * p.height) " ; " else " ")
                    }
                    append(p.line.text)
                }
            }
        }
        val centers = rows.map { r -> r.map { it.y }.average().toFloat() }
        val heights = rows.map { r -> r.map { it.height }.average().toFloat() }
        val steps = (1 until rows.size).map { centers[it] - centers[it - 1] }.sorted()
        val usual = steps.getOrNull(steps.size / 2) ?: 0f
        val lefts = rows.map { r -> r.minOf { it.left } }
        val rights = rows.map { r -> r.maxOf { it.right } }
        val widest = (rows.indices).maxOf { rights[it] - lefts[it] }

        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        for (i in rows.indices) {
            if (i > 0) {
                val step = centers[i] - centers[i - 1]
                // Nouveau paragraphe : interligne nettement plus grand que d'habitude, ou changement de taille.
                // Interligne plus grand que d'habitude, ou lignes très espacées (lettre : une ligne = une idée).
                val bigGap = step > usual * 1.45f || step > heights[i] * 1.5f
                val sizeChange = abs(heights[i] - heights[i - 1]) > 0.35f * maxOf(heights[i], heights[i - 1])
                val indented = lefts[i] - lefts[i - 1] > 1.5f * heights[i] && texts[i - 1].length > 20
                // Ligne précédente courte et finie par un point : fin de paragraphe.
                val shortEnd = rights[i - 1] - lefts[i - 1] < 0.7f * widest && texts[i - 1].trimEnd().lastOrNull() in END_MARKS &&
                    texts[i].firstOrNull()?.isUpperCase() == true
                if (bigGap || sizeChange || indented || shortEnd) {
                    paragraphs += current.toString()
                    current.clear()
                }
            }
            join(current, texts[i])
        }
        if (current.isNotEmpty()) paragraphs += current.toString()
        return paragraphs.filter { it.isNotBlank() }
    }

    /** Ajoute une ligne au paragraphe ; « para- » + « graphe » donne « paragraphe ». */
    private fun join(sb: StringBuilder, line: String) {
        if (sb.isEmpty()) {
            sb.append(line)
            return
        }
        val last = sb.last()
        if (last == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && line.firstOrNull()?.isLowerCase() == true) {
            sb.setLength(sb.length - 1)
            sb.append(line)
        } else {
            sb.append(' ').append(line)
        }
    }
}
