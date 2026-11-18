package io.github.thomaszimmer13.xfiles.data.ocr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Image en mémoire, pixels ARGB ligne par ligne (indépendante d'Android, pour les tests). */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height)
    }

    /** Redimensionnement bilinéaire. */
    fun resized(newWidth: Int, newHeight: Int): RgbImage {
        if (newWidth == width && newHeight == height) return this
        val out = IntArray(newWidth * newHeight)
        val sx = width.toFloat() / newWidth
        val sy = height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            for (x in 0 until newWidth) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                out[y * newWidth + x] = sample(fx, fy)
            }
        }
        return RgbImage(newWidth, newHeight, out)
    }

    /** Pixel interpolé (bilinéaire) à une position fractionnaire ; hors image : bord le plus proche. */
    fun sample(fx: Float, fy: Float): Int {
        val cx = fx.coerceIn(0f, (width - 1).toFloat())
        val cy = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val ax = cx - x0
        val ay = cy - y0
        val p00 = pixels[y0 * width + x0]
        val p10 = pixels[y0 * width + x1]
        val p01 = pixels[y1 * width + x0]
        val p11 = pixels[y1 * width + x1]
        fun ch(shift: Int): Int {
            val a = (p00 shr shift and 0xFF) * (1 - ax) + (p10 shr shift and 0xFF) * ax
            val b = (p01 shr shift and 0xFF) * (1 - ax) + (p11 shr shift and 0xFF) * ax
            return (a * (1 - ay) + b * ay).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Rotation d'un quart de tour vers la droite ([quarters] fois). */
    fun rotated(quarters: Int): RgbImage {
        val q = ((quarters % 4) + 4) % 4
        if (q == 0) return this
        val w = if (q % 2 == 0) width else height
        val h = if (q % 2 == 0) height else width
        val out = IntArray(w * h)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (nx, ny) = when (q) {
                    1 -> (height - 1 - y) to x
                    2 -> (width - 1 - x) to (height - 1 - y)
                    else -> y to (width - 1 - x)
                }
                out[ny * w + nx] = pixels[y * width + x]
            }
        }
        return RgbImage(w, h, out)
    }

    companion object {
        /** Taille réduite pour que le plus grand côté ne dépasse pas [maxSide] (multiple de [multiple]). */
        fun fitted(width: Int, height: Int, maxSide: Int, multiple: Int = 1): Pair<Int, Int> {
            val scale = min(1f, maxSide.toFloat() / max(width, height))
            fun round(v: Float) = max(multiple, (v / multiple).roundToInt() * multiple)
            return round(width * scale) to round(height * scale)
        }
    }
}
