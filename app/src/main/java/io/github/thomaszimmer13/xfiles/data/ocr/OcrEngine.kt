package io.github.thomaszimmer13.xfiles.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Une ligne reconnue, avec sa position dans l'image (repère d'origine). */
data class OcrLine(val text: String, val box: OrientedBox, val confidence: Float)

/** Résultat d'une page : lignes et paragraphes dans l'ordre de lecture. */
data class OcrPage(val lines: List<OcrLine>, val paragraphs: List<String>, val quarterTurns: Int) {
    val text: String get() = paragraphs.joinToString("\n\n")
}

/**
 * Reconnaissance de texte PaddleOCR PP-OCRv5 (détection DB + reconnaissance latine CTC),
 * entièrement sur l'appareil, avec ONNX Runtime.
 *
 * Sans OpenCV : composantes connexes, enveloppe convexe et rectangle minimal sont faits ici.
 * L'orientation de la page (quart de tour, tête en bas) est choisie d'après la confiance de lecture.
 */
class OcrEngine(detModel: File, recModel: File, private val dictionary: List<String>, threads: Int = 4) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det = env.createSession(detModel.path, options)
    private val rec = env.createSession(recModel.path, options)

    /** Nombre de classes : blanc + dictionnaire + espace. */
    private val symbols = dictionary + " "

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    fun recognize(image: RgbImage, cancelled: () -> Boolean = { false }): OcrPage {
        val boxes = detect(image)
        if (boxes.isEmpty() || cancelled()) return OcrPage(emptyList(), emptyList(), 0)

        // Orientation : lignes plutôt verticales → page tournée d'un quart de tour.
        val tall = boxes.count { it.height > it.width * 1.5f && it.height > 12 }
        val candidates = if (tall > boxes.size * 0.6f) listOf(1, 3) else listOf(0, 2)
        val sample = boxes.sortedByDescending { it.width * it.height }.take(SAMPLE_LINES)
        val turns = if (candidates == listOf(0, 2)) {
            val upright = meanConfidence(recognizeBoxes(image, sample.map { orient(it, 0) }))
            if (upright >= 0.9f) {
                0
            } else {
                val flipped = meanConfidence(recognizeBoxes(image, sample.map { orient(it, 2) }))
                if (flipped > upright + 0.05f) 2 else 0
            }
        } else {
            val a = meanConfidence(recognizeBoxes(image, sample.map { orient(it, 1) }))
            val b = meanConfidence(recognizeBoxes(image, sample.map { orient(it, 3) }))
            if (b > a) 3 else 1
        }
        if (cancelled()) return OcrPage(emptyList(), emptyList(), turns)
        val oriented = boxes.map { orient(it, turns) }
        val lines = recognizeBoxes(image, oriented, cancelled)
            .filter { it.confidence >= MIN_CONFIDENCE && it.text.isNotBlank() }
        return OcrPage(lines, OcrLayout.paragraphs(lines), turns)
    }

    /** Axe du texte d'après l'orientation de la page (en quarts de tour). */
    private fun orient(box: OrientedBox, turns: Int): OrientedBox = when (turns) {
        1 -> box.quarterTurned(clockwise = true)
        2 -> box.halfTurned()
        3 -> box.quarterTurned(clockwise = false)
        else -> box
    }

    private fun meanConfidence(lines: List<OcrLine>) =
        if (lines.isEmpty()) 0f else lines.map { if (it.text.isBlank()) 0f else it.confidence }.average().toFloat()

    // --- Détection ---

    fun detect(image: RgbImage): List<OrientedBox> {
        val (w, h) = RgbImage.fitted(image.width, image.height, DET_MAX_SIDE, 32)
        val small = image.resized(w, h)
        val input = FloatBuffer.allocate(3 * w * h)
        val plane = w * h
        // Canaux B, G, R (ordre d'entraînement de PaddleOCR), normalisation ImageNet.
        for (i in 0 until plane) {
            val p = small.pixels[i]
            val b = (p and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val r = (p shr 16 and 0xFF) / 255f
            input.put(i, (b - 0.485f) / 0.229f)
            input.put(plane + i, (g - 0.456f) / 0.224f)
            input.put(2 * plane + i, (r - 0.406f) / 0.225f)
        }
        val prob = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            det.run(mapOf(det.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }
        val sx = image.width.toFloat() / w
        val sy = image.height.toFloat() / h
        return boxesFromMap(prob, w, h).map { box ->
            OrientedBox.fromCorners(box.corners().map { (x, y) -> Pair(x * sx, y * sy) })
        }
    }

    /** Carte de probabilité → rectangles (post-traitement DB). */
    internal fun boxesFromMap(prob: FloatArray, w: Int, h: Int): List<OrientedBox> {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val result = ArrayList<OrientedBox>()
        var next = 0
        for (start in 0 until w * h) {
            if (label[start] != 0 || prob[start] <= THRESHOLD) continue
            next++
            label[start] = next
            var top = 0
            stack[top++] = start
            var count = 0
            var sum = 0.0
            val rowMin = HashMap<Int, Int>()
            val rowMax = HashMap<Int, Int>()
            while (top > 0) {
                val p = stack[--top]
                val x = p % w
                val y = p / w
                count++
                sum += prob[p]
                rowMin[y] = min(rowMin[y] ?: x, x)
                rowMax[y] = max(rowMax[y] ?: x, x)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (label[q] == 0 && prob[q] > THRESHOLD) {
                        label[q] = next
                        stack[top++] = q
                    }
                }
            }
            if (count < MIN_PIXELS || sum / count < BOX_THRESHOLD) continue
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for ((y, x0) in rowMin) {
                val x1 = rowMax.getValue(y)
                xs += x0.toFloat(); ys += y.toFloat()
                xs += x0.toFloat(); ys += (y + 1).toFloat()
                xs += (x1 + 1).toFloat(); ys += y.toFloat()
                xs += (x1 + 1).toFloat(); ys += (y + 1).toFloat()
            }
            val rect = Geometry.minAreaRect(Geometry.convexHull(xs.toFloatArray(), ys.toFloatArray())) ?: continue
            if (min(rect.width, rect.height) < MIN_SIDE) continue
            // « Unclip » : la carte ne couvre que le cœur des lettres, on élargit le rectangle.
            val d = rect.width * rect.height * UNCLIP_RATIO / (2 * (rect.width + rect.height))
            result += rect.copy(width = rect.width + 2 * d, height = rect.height + 2 * d)
        }
        return result
    }

    // --- Reconnaissance ---

    fun recognizeBoxes(image: RgbImage, boxes: List<OrientedBox>, cancelled: () -> Boolean = { false }): List<OcrLine> {
        val widths = boxes.map { b -> (ceil(REC_HEIGHT * b.width / max(1f, b.height)).toInt()).coerceIn(8, REC_MAX_WIDTH) }
        val order = boxes.indices.sortedBy { widths[it] }
        val results = arrayOfNulls<OcrLine>(boxes.size)
        var i = 0
        while (i < order.size) {
            if (cancelled()) break
            // Lots de lignes de largeurs voisines : moins de calcul perdu en remplissage.
            val batch = ArrayList<Int>()
            val first = widths[order[i]]
            while (i < order.size && batch.size < REC_BATCH && widths[order[i]] <= first * 2) batch += order[i++]
            val batchWidth = batch.maxOf { widths[it] }
            val plane = REC_HEIGHT * batchWidth
            val input = FloatBuffer.allocate(batch.size * 3 * plane)
            batch.forEachIndexed { n, index -> fillLine(image, boxes[index], widths[index], batchWidth, input, n * 3 * plane) }
            OnnxTensor.createTensor(env, input, longArrayOf(batch.size.toLong(), 3, REC_HEIGHT.toLong(), batchWidth.toLong())).use { tensor ->
                rec.run(mapOf(rec.inputNames.first() to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val shape = out.info.shape
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buffer = out.floatBuffer
                    batch.forEachIndexed { n, index ->
                        val (text, conf) = ctcDecode(buffer, n * steps * classes, steps, classes)
                        results[index] = OcrLine(text, boxes[index], conf)
                    }
                }
            }
        }
        return results.mapIndexed { k, line -> line ?: OcrLine("", boxes[k], 0f) }
    }

    /** Découpe la ligne (rectangle orienté) directement à 48 pixels de haut, normalisée, canaux B G R. */
    private fun fillLine(image: RgbImage, box: OrientedBox, width: Int, batchWidth: Int, out: FloatBuffer, offset: Int) {
        val plane = REC_HEIGHT * batchWidth
        val ux = box.ux
        val uy = box.uy
        val vx = -uy
        val vy = ux
        for (j in 0 until REC_HEIGHT) {
            val t = ((j + 0.5f) / REC_HEIGHT - 0.5f) * box.height
            for (i in 0 until batchWidth) {
                val idx = j * batchWidth + i
                if (i >= width) {
                    out.put(offset + idx, 0f)
                    out.put(offset + plane + idx, 0f)
                    out.put(offset + 2 * plane + idx, 0f)
                    continue
                }
                val s = ((i + 0.5f) / width - 0.5f) * box.width
                val p = image.sample(box.cx + s * ux + t * vx - 0.5f, box.cy + s * uy + t * vy - 0.5f)
                out.put(offset + idx, (p and 0xFF) / 127.5f - 1f)
                out.put(offset + plane + idx, (p shr 8 and 0xFF) / 127.5f - 1f)
                out.put(offset + 2 * plane + idx, (p shr 16 and 0xFF) / 127.5f - 1f)
            }
        }
    }

    private fun ctcDecode(buffer: FloatBuffer, offset: Int, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var last = 0
        var confSum = 0f
        var kept = 0
        for (t in 0 until steps) {
            var best = 0
            var bestP = -1f
            val base = offset + t * classes
            for (c in 0 until classes) {
                val p = buffer.get(base + c)
                if (p > bestP) {
                    bestP = p
                    best = c
                }
            }
            if (best != 0 && best != last && best - 1 < symbols.size) {
                sb.append(symbols[best - 1])
                confSum += bestP
                kept++
            }
            last = best
        }
        return sb.toString().trim() to if (kept == 0) 0f else confSum / kept
    }

    companion object {
        const val DET_MAX_SIDE = 1536
        const val THRESHOLD = 0.3f
        const val BOX_THRESHOLD = 0.6f
        const val UNCLIP_RATIO = 1.6f
        const val MIN_PIXELS = 6
        const val MIN_SIDE = 3f
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 3200
        const val REC_BATCH = 8
        const val SAMPLE_LINES = 6
        const val MIN_CONFIDENCE = 0.5f

        fun loadDictionary(lines: Sequence<String>): List<String> = lines.filter { it.isNotEmpty() }.toList()
    }
}
