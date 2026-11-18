package io.github.thomaszimmer13.xfiles.data.ocr

import android.content.Context
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Une page scannée : la photo (JPEG) et les lignes reconnues, dans le repère de la photo. */
data class ScannedPage(val image: File, val width: Int, val height: Int, val lines: List<OcrLine>, val paragraphs: List<String>)

/**
 * PDF « cherchable » : chaque page est la photo, avec par-dessus le texte reconnu, invisible, placé
 * sur chaque ligne. Les lecteurs d'écran, la recherche et le copier-coller y ont accès.
 */
object ScanPdf {
    private const val FONT = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    private const val PAGE_WIDTH = 595f // A4 en points

    fun write(context: Context, pages: List<ScannedPage>, target: File, title: String) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument().use { doc ->
            doc.documentInformation.title = title
            doc.documentInformation.creator = "XFiles"
            val font = context.assets.open(FONT).use { PDType0Font.load(doc, it) }
            for (page in pages) {
                val bitmap = BitmapFactory.decodeFile(page.image.path) ?: continue
                val scale = PAGE_WIDTH / bitmap.width
                val pageHeight = bitmap.height * scale
                val pdfPage = PDPage(PDRectangle(PAGE_WIDTH, pageHeight))
                doc.addPage(pdfPage)
                val image = JPEGFactory.createFromImage(doc, bitmap, 0.8f)
                bitmap.recycle()
                // Les rectangles des lignes sont dans le repère de la photo d'origine.
                val sx = PAGE_WIDTH / page.width
                PDPageContentStream(doc, pdfPage).use { cs ->
                    cs.drawImage(image, 0f, 0f, PAGE_WIDTH, pageHeight)
                    cs.beginText()
                    cs.setRenderingMode(RenderingMode.NEITHER)
                    for (line in page.lines) {
                        val text = encodable(font, line.text)
                        if (text.isBlank()) continue
                        val b = line.box
                        val size = (b.height * sx * 0.72f).coerceAtLeast(1f)
                        val natural = font.getStringWidth(text) / 1000f * size
                        if (natural <= 0f) continue
                        val stretch = b.width * sx * 0.95f / natural
                        // Début de la ligne de base, un peu sous le milieu du rectangle (y vers le bas dans la photo).
                        val vx = -b.uy
                        val vy = b.ux
                        val px = b.cx - b.ux * b.width / 2 + vx * b.height * 0.22f
                        val py = b.cy - b.uy * b.width / 2 + vy * b.height * 0.22f
                        val a = -b.angle
                        cs.setFont(font, size)
                        cs.setTextMatrix(Matrix(cos(a) * stretch, sin(a) * stretch, -sin(a), cos(a), px * sx, pageHeight - py * sx))
                        cs.showText(text)
                    }
                    cs.endText()
                }
            }
            doc.save(target)
        }
    }

    /** Retire les caractères que la police ne sait pas écrire (sinon PDFBox échoue). */
    private fun encodable(font: PDType0Font, text: String): String = try {
        font.encode(text)
        text
    } catch (_: Exception) {
        buildString {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val s = String(Character.toChars(cp))
                if (runCatching { font.encode(s) }.isSuccess) append(s) else append(' ')
                i += Character.charCount(cp)
            }
        }
    }
}
