package io.github.thomaszimmer13.xfiles.data.ocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import io.github.thomaszimmer13.xfiles.data.document.PdfText
import io.github.thomaszimmer13.xfiles.data.ocr.OcrService.Companion.toRgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun line(x: Float, y: Float, w: Float, h: Float = 30f) = OrientedBox(x + w / 2, y + h / 2, w, h, 0f)

    @Test fun guidance() {
        val w = 480
        val h = 640
        assertEquals(Guidance.NO_TEXT, ScanGuide.evaluate(emptyList(), w, h))
        val page = (0 until 12).map { line(60f, 80f + it * 40f, 360f) }
        assertEquals(Guidance.READY, ScanGuide.evaluate(page, w, h))
        assertEquals(Guidance.CUT_TOP, ScanGuide.evaluate(page + line(60f, 2f, 360f), w, h))
        assertEquals(Guidance.CUT_RIGHT, ScanGuide.evaluate(page + line(200f, 100f, 279f), w, h))
        assertEquals(Guidance.MOVE_BACK, ScanGuide.evaluate(page + line(60f, 1f, 360f) + line(60f, 615f, 360f), w, h))
        val far = (0 until 4).map { line(200f, 300f + it * 10f, 60f, 6f) }
        assertEquals(Guidance.MOVE_CLOSER, ScanGuide.evaluate(far, w, h))
    }

    /** Photo → reconnaissance → PDF cherchable → relu par le lecteur de documents. */
    @Test fun searchablePdf() {
        val dir = System.getenv("XFILES_OCR_MODELS")?.let(::File)
        assumeTrue("Modèles OCR absents", dir != null && File(dir, "det.onnx").exists())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val photo = File(dir, "photo.jpg")
        val bitmap = BitmapFactory.decodeFile(photo.path)
        val dict = OcrEngine.loadDictionary(File("src/main/assets/ocr/latin_dict.txt").readLines().asSequence())
        val page = OcrEngine(File(dir!!, "det.onnx"), File(dir, "rec.onnx"), dict).use { it.recognize(bitmap.toRgbImage()) }
        val pdf = File(tmp.root, "scan.pdf")
        ScanPdf.write(context, listOf(ScannedPage(photo, bitmap.width, bitmap.height, page.lines, page.paragraphs)), pdf, "Scan")
        val doc = PdfText(context).read(pdf)
        val text = doc.blocks.joinToString("\n") { it.text }
        println(text)
        assertEquals(1, doc.pages)
        listOf("Mairie de Lyon", "carte d'identité", "04 72 10 30 30", "etat-civil@mairie-lyon.fr", "salutations distinguées").forEach {
            assertTrue("« $it » absent du PDF :\n$text", it in text)
        }
    }
}
