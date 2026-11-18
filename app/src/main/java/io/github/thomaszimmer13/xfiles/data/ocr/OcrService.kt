package io.github.thomaszimmer13.xfiles.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** État des modèles de reconnaissance (téléchargés une seule fois). */
sealed interface OcrModelState {
    data object Missing : OcrModelState
    data class Downloading(val fraction: Float) : OcrModelState
    data object Ready : OcrModelState
    data class Failed(val reason: String) : OcrModelState
}

/**
 * Reconnaissance de texte pour l'app : modèles, moteur partagé, images et pages PDF.
 * Les modèles PP-OCRv5 (Apache 2.0) sont téléchargés depuis Hugging Face à une révision fixe
 * et vérifiés par leur empreinte SHA-256.
 */
class OcrService(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "ocr")
    private val cacheDir = File(app.cacheDir, "ocr")
    private val mutex = Mutex()
    private var engine: OcrEngine? = null
    private var idleJob: Job? = null
    private var downloadJob: Job? = null

    private class Model(val name: String, val url: String, val sha256: String, val size: Long)

    private val models = listOf(
        Model(
            "det.onnx",
            "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/e6f4fa85f00e168c862bc462aebca69eef9b3d3d/inference.onnx",
            "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
            4_826_518,
        ),
        Model(
            "rec-latin.onnx",
            "https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx/resolve/89d3a50e2c27e2e7cceeab0e944c25c807d5db4f/inference.onnx",
            "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498",
            8_042_023,
        ),
    )

    /** Taille totale à télécharger, en octets. */
    val downloadSize: Long = models.sumOf { it.size }

    private val _state = MutableStateFlow(if (installed()) OcrModelState.Ready else OcrModelState.Missing)
    val state: StateFlow<OcrModelState> = _state.asStateFlow()

    private fun installed() = models.all { File(dir, it.name).length() == it.size }

    fun download() {
        if (downloadJob?.isActive == true || installed()) {
            if (installed()) _state.value = OcrModelState.Ready
            return
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            _state.value = OcrModelState.Downloading(0f)
            dir.mkdirs()
            var done = 0L
            try {
                for (model in models) {
                    val target = File(dir, model.name)
                    if (target.length() == model.size) {
                        done += model.size
                        continue
                    }
                    val tmp = File(dir, model.name + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val connection = URL(model.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = true
                    try {
                        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                        connection.inputStream.use { input ->
                            tmp.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!coroutineContext.isActive) throw IOException("cancelled")
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    done += n
                                    _state.value = OcrModelState.Downloading((done.toFloat() / downloadSize).coerceAtMost(1f))
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (hash != model.sha256) {
                        tmp.delete()
                        throw IOException("checksum")
                    }
                    if (!tmp.renameTo(target)) throw IOException("rename")
                }
                _state.value = OcrModelState.Ready
            } catch (e: Exception) {
                dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
                _state.value = OcrModelState.Failed(e.message ?: "error")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = if (installed()) OcrModelState.Ready else OcrModelState.Missing
    }

    suspend fun deleteModels() = mutex.withLock {
        engine?.close()
        engine = null
        dir.deleteRecursively()
        cacheDir.deleteRecursively()
        _state.value = OcrModelState.Missing
    }

    /** Moteur partagé ; libéré (≈ 100 Mo de mémoire) après deux minutes sans servir. */
    private suspend fun <T> withEngine(block: (OcrEngine) -> T): T = mutex.withLock {
        idleJob?.cancel()
        val e = engine ?: run {
            val dict = app.assets.open("ocr/latin_dict.txt").bufferedReader().useLines { OcrEngine.loadDictionary(it) }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            OcrEngine(File(dir, "det.onnx"), File(dir, "rec-latin.onnx"), dict, threads)
        }.also { engine = it }
        try {
            block(e)
        } finally {
            idleJob = scope.launch {
                delay(120_000)
                mutex.withLock {
                    engine?.close()
                    engine = null
                }
            }
        }
    }

    suspend fun recognize(bitmap: Bitmap, cancelled: () -> Boolean = { false }): OcrPage = withContext(Dispatchers.Default) {
        val image = bitmap.toRgbImage()
        withEngine { it.recognize(image, cancelled) }
    }

    /** Détection seule, rapide (aperçu du scanner) : rectangles des lignes de texte. */
    suspend fun detect(bitmap: Bitmap, maxSide: Int = 640): List<OrientedBox> = withContext(Dispatchers.Default) {
        val image = bitmap.toRgbImage()
        withEngine { it.detect(image, maxSide) }
    }

    /** Photo ou capture d'écran, redressée selon ses informations EXIF. */
    suspend fun recognizeImage(file: File, cancelled: () -> Boolean = { false }): OcrPage {
        val bitmap = withContext(Dispatchers.IO) { decodeImage(file) } ?: throw IOException("image")
        return try {
            recognize(bitmap, cancelled)
        } finally {
            bitmap.recycle()
        }
    }

    fun pdfPageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }

    /** Page [index] (à partir de 0) d'un PDF scanné, rendue à environ 2000 pixels de côté. */
    suspend fun recognizePdfPage(file: File, index: Int, cancelled: () -> Boolean = { false }): OcrPage {
        val bitmap = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(index).use { page ->
                        val scale = PDF_SIDE.toFloat() / max(page.width, page.height)
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }
        return try {
            recognize(bitmap, cancelled)
        } finally {
            bitmap.recycle()
        }
    }

    // --- Cache des résultats : rouvrir un document déjà reconnu est immédiat. ---

    private fun cacheFile(file: File): File {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.txt")
    }

    /** Pages (paragraphes) déjà reconnues pour ce fichier, ou null. */
    fun cached(file: File): List<List<String>>? = cacheFile(file).takeIf { it.exists() }?.readText()?.let { text ->
        text.split(PAGE_SEPARATOR).map { page -> page.split('\n').filter { it.isNotBlank() } }
    }

    fun store(file: File, pages: List<List<String>>) {
        runCatching {
            cacheDir.mkdirs()
            cacheFile(file).writeText(pages.joinToString(PAGE_SEPARATOR) { it.joinToString("\n") })
        }
    }

    companion object {
        private const val PDF_SIDE = 2000
        private const val IMAGE_SIDE = 2400
        private const val PAGE_SEPARATOR = "\u000C"

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun Bitmap.toRgbImage(): RgbImage {
            val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
            val pixels = IntArray(src.width * src.height)
            src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
            if (src !== this) src.recycle()
            return RgbImage(src.width, src.height, pixels)
        }

        fun decodeImage(file: File): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val degrees = runCatching {
                when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
            if (degrees == 0) return bitmap
            val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        }
    }
}
