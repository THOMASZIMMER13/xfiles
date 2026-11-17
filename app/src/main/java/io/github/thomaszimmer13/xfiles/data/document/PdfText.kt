package io.github.thomaszimmer13.xfiles.data.document

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Texte d'un PDF, page par page, avec les lignes recollées en paragraphes. */
class PdfText(context: Context) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Mot de passe requis : le lecteur le demande puis réessaie. */
    class PasswordRequired : Exception()

    fun read(file: File, password: String? = null): Document {
        val doc = try {
            if (password != null) PDDocument.load(file, password) else PDDocument.load(file)
        } catch (_: InvalidPasswordException) {
            throw PasswordRequired()
        }
        return doc.use { pdf ->
            val pages = pdf.numberOfPages
            val title = pdf.documentInformation?.title.orEmpty()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
                paragraphStart = "\n "
            }
            val blocks = mutableListOf<DocBlock>()
            var chars = 0
            for (page in 1..pages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf)
                blocks += DocBlock.PageStart(page)
                for (paragraph in paragraphs(text)) {
                    blocks += DocBlock.Paragraph(paragraph, Linkifier.find(paragraph))
                    chars += paragraph.length
                }
                if (chars > 4_000_000) return@use Document(title, blocks, pages = pages, truncated = true)
            }
            // Que des débuts de page : PDF scanné (images), sans texte.
            if (blocks.all { it is DocBlock.PageStart }) throw DocumentException("pdf-image")
            Document(title, blocks, pages = pages)
        }
    }

    companion object {
        /**
         * Recolle les lignes coupées : une ligne vide ou un saut de paragraphe sépare les paragraphes,
         * un trait d'union en fin de ligne est retiré (« docu-\\nment » → « document »).
         */
        fun paragraphs(text: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() {
                val p = current.toString().replace(Regex("\\s+"), " ").trim()
                if (p.isNotEmpty()) result += p
                current.clear()
            }
            for (raw in text.split('\n')) {
                val startsParagraph = raw.startsWith(" ")
                val line = raw.removePrefix(" ").trim()
                if (line.isEmpty() || startsParagraph) flush()
                if (line.isEmpty()) continue
                if (current.endsWith("-") && current.length > 1 && current[current.length - 2].isLetter()) {
                    current.setLength(current.length - 1)
                    current.append(line)
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line)
                }
            }
            flush()
            return result
        }
    }
}
