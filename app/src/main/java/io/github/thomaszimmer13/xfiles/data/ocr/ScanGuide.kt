package io.github.thomaszimmer13.xfiles.data.ocr

/** Conseil donné pendant le cadrage d'une page. */
enum class Guidance { NO_TEXT, CUT_TOP, CUT_BOTTOM, CUT_LEFT, CUT_RIGHT, MOVE_BACK, MOVE_CLOSER, READY }

/**
 * Guidage du scanner d'après les lignes de texte vues dans l'aperçu (image tournée comme l'écran).
 * Le texte touche un bord : la page sort du cadre de ce côté. Il touche deux bords opposés :
 * le téléphone est trop près. Il est minuscule ou n'occupe qu'un coin : trop loin.
 */
object ScanGuide {
    fun evaluate(boxes: List<OrientedBox>, width: Int, height: Int): Guidance {
        val lines = boxes.filter { it.width > it.height && it.width > width * 0.04f }
        if (lines.size < 2) return Guidance.NO_TEXT
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (b in lines) {
            for ((x, y) in b.corners()) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val mx = width * EDGE
        val my = height * EDGE
        val top = minY < my
        val bottom = maxY > height - my
        val left = minX < mx
        val right = maxX > width - mx
        if ((top && bottom) || (left && right)) return Guidance.MOVE_BACK
        val heights = lines.map { it.height }.sorted()
        val lineHeight = heights[heights.size / 2]
        val coverage = (maxX - minX) * (maxY - minY) / (width.toFloat() * height)
        return when {
            top -> Guidance.CUT_TOP
            bottom -> Guidance.CUT_BOTTOM
            left -> Guidance.CUT_LEFT
            right -> Guidance.CUT_RIGHT
            lineHeight < height * MIN_LINE || coverage < MIN_COVERAGE -> Guidance.MOVE_CLOSER
            else -> Guidance.READY
        }
    }

    private const val EDGE = 0.025f
    private const val MIN_LINE = 0.014f
    private const val MIN_COVERAGE = 0.18f
}
