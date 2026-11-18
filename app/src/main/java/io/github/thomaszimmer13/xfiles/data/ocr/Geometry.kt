package io.github.thomaszimmer13.xfiles.data.ocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Rectangle orienté : centre, largeur le long de l'axe [angle] (radians, sens des aiguilles d'une montre
 * puisque y descend), hauteur perpendiculaire.
 */
data class OrientedBox(val cx: Float, val cy: Float, val width: Float, val height: Float, val angle: Float) {
    val ux get() = cos(angle)
    val uy get() = sin(angle)

    /** Les quatre coins : haut-gauche, haut-droit, bas-droit, bas-gauche (dans le repère du texte). */
    fun corners(): List<Pair<Float, Float>> {
        val hw = width / 2
        val hh = height / 2
        val vx = -uy
        val vy = ux
        return listOf(
            Pair(cx - hw * ux - hh * vx, cy - hw * uy - hh * vy),
            Pair(cx + hw * ux - hh * vx, cy + hw * uy - hh * vy),
            Pair(cx + hw * ux + hh * vx, cy + hw * uy + hh * vy),
            Pair(cx - hw * ux + hh * vx, cy - hw * uy + hh * vy),
        )
    }

    /** Même rectangle tourné d'un quart de tour (l'axe du texte devient l'autre côté). */
    fun quarterTurned(clockwise: Boolean): OrientedBox =
        copy(width = height, height = width, angle = angle + if (clockwise) HALF_PI else -HALF_PI)

    fun halfTurned(): OrientedBox = copy(angle = angle + PI)

    companion object {
        const val PI = Math.PI.toFloat()
        const val HALF_PI = (Math.PI / 2).toFloat()

        /** Rectangle passant par quatre coins (ordre de [corners]). */
        fun fromCorners(c: List<Pair<Float, Float>>): OrientedBox {
            val (x0, y0) = c[0]
            val (x1, y1) = c[1]
            val (x3, y3) = c[3]
            val cx = c.sumOf { it.first.toDouble() }.toFloat() / 4
            val cy = c.sumOf { it.second.toDouble() }.toFloat() / 4
            return OrientedBox(cx, cy, hypot(x1 - x0, y1 - y0), hypot(x3 - x0, y3 - y0), atan2(y1 - y0, x1 - x0))
        }
    }
}

object Geometry {
    /** Enveloppe convexe (chaîne monotone d'Andrew), points en (x, y) entrelacés. */
    fun convexHull(xs: FloatArray, ys: FloatArray): List<Pair<Float, Float>> {
        val pts = xs.indices.map { Pair(xs[it], ys[it]) }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (pts.size < 3) return pts
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Float, Float>>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<Pair<Float, Float>>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * Plus petit rectangle orienté contenant l'enveloppe (« pieds à coulisse » sur chaque côté).
     * L'axe retenu est le plus proche de l'horizontale : la largeur suit les lignes de texte.
     */
    fun minAreaRect(hull: List<Pair<Float, Float>>): OrientedBox? {
        if (hull.size < 3) return null
        var best: OrientedBox? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val (ax, ay) = hull[i]
            val (bx, by) = hull[(i + 1) % hull.size]
            val len = hypot(bx - ax, by - ay)
            if (len < 1e-6f) continue
            val ux = (bx - ax) / len
            val uy = (by - ay) / len
            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            for ((px, py) in hull) {
                val u = px * ux + py * uy
                val v = -px * uy + py * ux
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                val cu = (minU + maxU) / 2
                val cv = (minV + maxV) / 2
                best = OrientedBox(cu * ux - cv * uy, cu * uy + cv * ux, maxU - minU, maxV - minV, atan2(uy, ux))
            }
        }
        return best?.let(::nearHorizontal)
    }

    /** Ramène l'axe entre -45° et +45° (en échangeant largeur et hauteur si besoin). */
    fun nearHorizontal(box: OrientedBox): OrientedBox {
        var b = box
        var a = b.angle
        while (a > OrientedBox.PI) a -= 2 * OrientedBox.PI
        while (a <= -OrientedBox.PI) a += 2 * OrientedBox.PI
        b = b.copy(angle = a)
        repeat(4) {
            if (abs(b.angle) <= OrientedBox.PI / 4 + 1e-4f) return b
            b = if (b.angle > 0) b.quarterTurned(clockwise = false) else b.quarterTurned(clockwise = true)
        }
        return b
    }
}
