package io.github.thomaszimmer13.xfiles.data.document

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Lecture des documents en blocs accessibles (titres, paragraphes, listes, tableaux, liens). */
object DocumentReader {

    private val TEXT = setOf("txt", "text", "log", "ini", "conf", "cfg", "properties", "yml", "yaml", "toml", "srt", "vtt", "nfo")
    private val CODE = setOf("json", "xml", "kt", "java", "py", "js", "ts", "sh", "lua", "c", "h", "cpp", "css", "sql", "gradle", "kts")
    private val MARKDOWN = setOf("md", "markdown")
    private val HTML = setOf("html", "htm", "xhtml")

    /** Formats lisibles dans le lecteur intégré. */
    fun supports(name: String): Boolean = ext(name) in TEXT + CODE + MARKDOWN + HTML + setOf("csv", "docx", "odt", "epub", "pdf")

    /** Formats modifiables dans l'éditeur de texte. */
    fun editable(name: String): Boolean = ext(name) in TEXT + CODE + MARKDOWN + setOf("csv", "html", "htm")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Limite de lecture : au-delà, le document est coupé et signalé comme tel. */
    private const val MAX_CHARS = 4_000_000

    /** Lit [file] ; les PDF passent par [pdf] (dépend d'Android). */
    fun read(file: File, pdf: ((File) -> Document)? = null): Document {
        if (!file.isFile) throw DocumentException("introuvable")
        val name = file.name
        val title = file.nameWithoutExtension
        return when (ext(name)) {
            in MARKDOWN -> markdown(title, decode(file))
            in HTML -> html(title, Jsoup.parse(decode(file).first))
            "csv" -> csv(title, decode(file))
            "docx" -> docx(file)
            "odt" -> odt(file)
            "epub" -> epub(file)
            "pdf" -> pdf?.invoke(file) ?: throw DocumentException("pdf")
            in CODE -> plain(title, decode(file), code = true)
            else -> plain(title, decode(file), code = false)
        }.let { if (it.title.isBlank()) it.copy(title = title) else it }
    }

    // --- Décodage du texte ---

    /** Texte du fichier (UTF-8, sinon Windows-1252) et indication de troncature. */
    fun decode(file: File): Pair<String, Boolean> {
        // readNBytes n'existe qu'à partir d'Android 13 : lecture bornée à la main.
        val bytes = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= MAX_CHARS) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val truncated = bytes.size > MAX_CHARS
        return decodeBytes(if (truncated) bytes.copyOf(MAX_CHARS) else bytes) to truncated
    }

    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, charset("windows-1252"))
        }
    }

    // --- Texte brut, code, CSV ---

    /** Une ligne non vide = un bloc : TalkBack avance ligne par ligne, comme dans un éditeur. */
    private fun plain(title: String, text: Pair<String, Boolean>, code: Boolean): Document {
        val blocks = text.first.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.map { line ->
            if (code) DocBlock.Preformatted(line) else DocBlock.Paragraph(line, Linkifier.find(line))
        }.toList()
        return Document(title, blocks, editable = true, truncated = text.second)
    }

    private fun csv(title: String, text: Pair<String, Boolean>): Document {
        val lines = text.first.lineSequence().filter { it.isNotBlank() }.toList()
        val first = lines.firstOrNull().orEmpty()
        val separator = listOf(';', ',', '\t').maxByOrNull { c -> first.count { it == c } } ?: ','
        val blocks = lines.mapIndexed { i, line -> DocBlock.TableRow(splitCsv(line, separator), header = i == 0) }
        return Document(title, blocks, editable = true, truncated = text.second)
    }

    /** Découpe une ligne CSV en tenant compte des guillemets. */
    internal fun splitCsv(line: String, sep: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { cells += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    // --- Markdown ---

    private val MD_LINK = Regex("""\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)""")

    /** Retire la mise en forme Markdown et garde les liens. */
    private fun mdInline(raw: String): Pair<String, List<DocLink>> {
        val out = StringBuilder()
        val links = mutableListOf<DocLink>()
        var last = 0
        for (m in MD_LINK.findAll(raw)) {
            out.append(stripEmphasis(raw.substring(last, m.range.first)))
            val label = stripEmphasis(m.groupValues[1]).ifEmpty { m.groupValues[2] }
            val start = out.length
            out.append(label)
            links += DocLink(start, out.length, m.groupValues[2], LinkKind.WEB)
            last = m.range.last + 1
        }
        out.append(stripEmphasis(raw.substring(last)))
        val text = out.toString()
        return text to Linkifier.merge(links, text)
    }

    private fun stripEmphasis(s: String) =
        s.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "Image : $1")
            .replace(Regex("""(\*\*|__)(.+?)\1"""), "$2")
            .replace(Regex("""(?<![\w*])[*_](\S.*?)[*_](?![\w*])"""), "$1")
            .replace("`", "")

    private fun markdown(title: String, text: Pair<String, Boolean>): Document {
        val blocks = mutableListOf<DocBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) {
                val (t, l) = mdInline(paragraph.toString().trim())
                blocks += DocBlock.Paragraph(t, l)
            }
            paragraph.clear()
        }
        var fence: StringBuilder? = null
        for (rawLine in text.first.lines()) {
            val line = rawLine.trimEnd()
            if (fence != null) {
                if (line.trimStart().startsWith("```")) {
                    fence.toString().lines().filter { it.isNotBlank() }.forEach { blocks += DocBlock.Preformatted(it) }
                    fence = null
                } else {
                    fence.append(line).append('\n')
                }
                continue
            }
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            when {
                trimmed.startsWith("```") -> { flush(); fence = StringBuilder() }
                trimmed.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(trimmed) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    val (t, l) = mdInline(trimmed.drop(level).trim().trimEnd('#').trim())
                    blocks += DocBlock.Heading(level, t, l)
                }
                Regex("^[-*+]\\s").containsMatchIn(trimmed) -> {
                    flush()
                    val (t, l) = mdInline(trimmed.drop(2).trim())
                    blocks += DocBlock.ListItem(t, indent / 2, "•", l)
                }
                Regex("^\\d+[.)]\\s").containsMatchIn(trimmed) -> {
                    flush()
                    val marker = trimmed.substringBefore(' ')
                    val (t, l) = mdInline(trimmed.substringAfter(' ').trim())
                    blocks += DocBlock.ListItem(t, indent / 2, marker, l)
                }
                trimmed.startsWith("|") -> {
                    flush()
                    val cells = trimmed.trim('|').split('|').map { mdInline(it.trim()).first }
                    if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue
                    val header = blocks.lastOrNull() !is DocBlock.TableRow
                    blocks += DocBlock.TableRow(cells, header)
                }
                trimmed.matches(Regex("^([-*_])\\s*(\\1\\s*){2,}$")) -> flush()
                trimmed.startsWith(">") -> { flush(); paragraph.append(trimmed.removePrefix(">").trim()); flush() }
                else -> paragraph.append(if (paragraph.isEmpty()) "" else " ").append(trimmed)
            }
        }
        flush()
        return Document(title, blocks, editable = true, truncated = text.second)
    }

    // --- HTML (pages web, EPUB) ---

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "blockquote", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "pre", "hr",
        "dl", "dt", "dd", "nav", "body", "form", "address", "center",
    )
    private val SKIP_TAGS = setOf("script", "style", "noscript", "template", "head", "svg")

    fun html(title: String, doc: org.jsoup.nodes.Document): Document {
        val blocks = mutableListOf<DocBlock>()
        HtmlWalker(blocks).walk(doc.body() ?: doc)
        return Document(doc.title().ifBlank { title }, blocks)
    }

    private class Inline {
        val text = StringBuilder()
        val links = mutableListOf<DocLink>()
        fun isBlank() = text.isBlank()
    }

    private class HtmlWalker(val blocks: MutableList<DocBlock>) {
        private var pending = Inline()

        private fun flush() {
            if (!pending.isBlank()) blocks += paragraph(pending)
            pending = Inline()
        }

        fun walk(element: Element) {
            for (child in element.childNodes()) {
                if (child is Element && child.normalName() in SKIP_TAGS) continue
                if (child is Element && child.normalName() in BLOCK_TAGS) {
                    flush()
                    block(child)
                } else {
                    collect(child, pending)
                }
            }
            flush()
        }

        private fun block(el: Element) {
            when (val tag = el.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val inline = Inline().also { collectChildren(el, it) }
                    val (t, l) = finish(inline)
                    if (t.isNotEmpty()) blocks += DocBlock.Heading(tag[1].digitToInt(), t, l)
                }
                "ul", "ol" -> list(el, 0)
                "table" -> table(el)
                "pre" -> el.wholeText().lines().filter { it.isNotBlank() }.forEach { blocks += DocBlock.Preformatted(it.trimEnd()) }
                "hr" -> Unit
                "li" -> list(el.parent() ?: el, 0)
                else -> walk(el)
            }
        }

        private fun list(listEl: Element, level: Int) {
            var number = listEl.attr("start").toIntOrNull() ?: 1
            val ordered = listEl.normalName() == "ol"
            for (li in listEl.children()) {
                if (li.normalName() != "li") continue
                val inline = Inline()
                val nested = mutableListOf<Element>()
                for (node in li.childNodes()) {
                    if (node is Element && (node.normalName() == "ul" || node.normalName() == "ol")) nested.add(node) else collect(node, inline)
                }
                val (t, l) = finish(inline)
                if (t.isNotEmpty()) blocks += DocBlock.ListItem(t, level, if (ordered) "${number}." else "•", l)
                number++
                nested.forEach { list(it, level + 1) }
            }
        }

        private fun table(tableEl: Element) {
            for (tr in tableEl.select("tr")) {
                val cells = tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }
                if (cells.isEmpty()) continue
                blocks += DocBlock.TableRow(cells.map { it.text().trim() }, header = cells.all { it.normalName() == "th" })
            }
        }

        private fun collectChildren(el: Element, into: Inline) = el.childNodes().forEach { collect(it, into) }

        private fun collect(node: Node, into: Inline) {
            when (node) {
                is TextNode -> appendText(into, node.text())
                is Element -> when (node.normalName()) {
                    in SKIP_TAGS -> Unit
                    "br" -> appendText(into, " ")
                    "img" -> node.attr("alt").takeIf { it.isNotBlank() }?.let { appendText(into, " Image : $it ") }
                    "a" -> {
                        val href = node.attr("href")
                        val start = into.text.length
                        collectChildren(node, into)
                        val end = into.text.length
                        if (href.isNotBlank() && end > start) {
                            val kind = when {
                                href.startsWith("mailto:") -> LinkKind.EMAIL
                                href.startsWith("tel:") -> LinkKind.PHONE
                                href.startsWith("#") || !href.contains(':') -> LinkKind.INTERNAL
                                else -> LinkKind.WEB
                            }
                            into.links += DocLink(start, end, href, kind)
                        }
                    }
                    else -> if (node.normalName() in BLOCK_TAGS) {
                        // Bloc dans du texte en ligne (rare) : on sépare d'un espace.
                        appendText(into, " ")
                        collectChildren(node, into)
                        appendText(into, " ")
                    } else {
                        collectChildren(node, into)
                    }
                }
            }
        }

        private fun appendText(into: Inline, s: String) {
            val normalized = s.replace(Regex("\\s+"), " ")
            if (normalized == " " && (into.text.isEmpty() || into.text.last() == ' ')) return
            if (into.text.isNotEmpty() && into.text.last() == ' ' && normalized.startsWith(" ")) into.text.append(normalized.drop(1)) else into.text.append(normalized)
        }

        private fun paragraph(inline: Inline): DocBlock {
            val (t, l) = finish(inline)
            return DocBlock.Paragraph(t, l)
        }

        /** Texte sans espaces de bord, liens recalés, plus les liens trouvés dans le texte. */
        private fun finish(inline: Inline): Pair<String, List<DocLink>> {
            val raw = inline.text.toString()
            val lead = raw.length - raw.trimStart().length
            val text = raw.trim()
            val links = inline.links.mapNotNull {
                val s = (it.start - lead).coerceIn(0, text.length)
                val e = (it.end - lead).coerceIn(0, text.length)
                if (e > s) it.copy(start = s, end = e) else null
            }
            return text to Linkifier.merge(links, text)
        }
    }

    // --- Word (.docx) ---

    private fun xml(zip: ZipFile, path: String): org.jsoup.nodes.Document? =
        zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "", Parser.xmlParser()) } }

    private fun docx(file: File): Document = ZipFile(file).use { zip ->
        val doc = xml(zip, "word/document.xml") ?: throw DocumentException("docx")
        val rels = xml(zip, "word/_rels/document.xml.rels")?.select("Relationship")?.associate { it.attr("Id") to it.attr("Target") }.orEmpty()
        // Nom de chaque style (« heading 1 », « Titre 2 »…) pour reconnaître les titres.
        val styleNames = xml(zip, "word/styles.xml")?.getElementsByTag("w:style")?.associate { s ->
            s.attr("w:styleId") to (s.getElementsByTag("w:name").firstOrNull()?.attr("w:val") ?: s.attr("w:styleId"))
        }.orEmpty()
        val title = xml(zip, "docProps/core.xml")?.getElementsByTag("dc:title")?.firstOrNull()?.text().orEmpty()
        val body = doc.getElementsByTag("w:body").firstOrNull() ?: throw DocumentException("docx")
        val blocks = mutableListOf<DocBlock>()
        for (el in body.children()) {
            when (el.tagName()) {
                "w:p" -> docxParagraph(el, rels, styleNames)?.let(blocks::add)
                "w:tbl" -> for (tr in el.getElementsByTag("w:tr")) {
                    val cells = tr.getElementsByTag("w:tc").map { tc -> tc.getElementsByTag("w:p").joinToString(" ") { p -> docxText(p, rels).first }.trim() }
                    if (cells.any { it.isNotEmpty() }) blocks += DocBlock.TableRow(cells, header = tr.getElementsByTag("w:tblHeader").isNotEmpty())
                }
                "w:sdt" -> el.getElementsByTag("w:p").forEach { p -> docxParagraph(p, rels, styleNames)?.let(blocks::add) }
            }
        }
        Document(title, blocks)
    }

    private val HEADING_NAME = Regex("""(?i)^(heading|titre|title|überschrift|titolo|título)\s*(\d)?$""")

    private fun docxParagraph(p: Element, rels: Map<String, String>, styleNames: Map<String, String>): DocBlock? {
        val (text, links) = docxText(p, rels)
        if (text.isBlank()) return null
        val props = p.getElementsByTag("w:pPr").firstOrNull()
        val styleId = props?.getElementsByTag("w:pStyle")?.firstOrNull()?.attr("w:val").orEmpty()
        val styleName = styleNames[styleId] ?: styleId
        val outline = props?.getElementsByTag("w:outlineLvl")?.firstOrNull()?.attr("w:val")?.toIntOrNull()
        val heading = HEADING_NAME.find(styleName.trim()) ?: HEADING_NAME.find(styleId.replace(Regex("(\\D)(\\d)"), "$1 $2"))
        return when {
            heading != null -> DocBlock.Heading(heading.groupValues[2].toIntOrNull() ?: 1, text, links)
            outline != null && outline < 9 -> DocBlock.Heading(outline + 1, text, links)
            props?.getElementsByTag("w:numPr")?.isNotEmpty() == true -> {
                val level = props.getElementsByTag("w:ilvl").firstOrNull()?.attr("w:val")?.toIntOrNull() ?: 0
                DocBlock.ListItem(text, level, "•", links)
            }
            else -> DocBlock.Paragraph(text, links)
        }
    }

    private fun docxText(p: Element, rels: Map<String, String>): Pair<String, List<DocLink>> {
        val out = StringBuilder()
        val links = mutableListOf<DocLink>()
        fun run(el: Element) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "w:t" -> out.append(c.wholeText())
                    "w:tab" -> out.append(' ')
                    "w:br", "w:cr" -> out.append(' ')
                    "w:hyperlink" -> {
                        val start = out.length
                        run(c)
                        val target = rels[c.attr("r:id")] ?: c.attr("w:anchor").takeIf { it.isNotEmpty() }?.let { "#$it" }
                        if (target != null && out.length > start) {
                            links += DocLink(start, out.length, target, if (target.startsWith("#")) LinkKind.INTERNAL else if (target.startsWith("mailto:")) LinkKind.EMAIL else LinkKind.WEB)
                        }
                    }
                    "w:pPr", "w:rPr", "w:del", "w:instrText" -> Unit
                    else -> run(c)
                }
            }
        }
        run(p)
        val text = out.toString().replace(Regex("\\s+"), " ").trim()
        // Recalage simple : les liens restent valides si le texte n'a perdu que des espaces de début.
        val lead = out.length - out.toString().trimStart().length
        val fixed = links.mapNotNull { l ->
            val s = (l.start - lead).coerceIn(0, text.length)
            val e = (l.end - lead).coerceIn(0, text.length)
            if (e > s) l.copy(start = s, end = e) else null
        }
        return text to Linkifier.merge(fixed, text)
    }

    // --- OpenDocument (.odt) ---

    private fun odt(file: File): Document = ZipFile(file).use { zip ->
        val doc = xml(zip, "content.xml") ?: throw DocumentException("odt")
        val title = xml(zip, "meta.xml")?.getElementsByTag("dc:title")?.firstOrNull()?.text().orEmpty()
        val body = doc.getElementsByTag("office:text").firstOrNull() ?: throw DocumentException("odt")
        val blocks = mutableListOf<DocBlock>()
        fun walk(el: Element, listLevel: Int) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "text:h" -> odtText(c).let { (t, l) -> if (t.isNotBlank()) blocks += DocBlock.Heading(c.attr("text:outline-level").toIntOrNull() ?: 1, t, l) }
                    "text:p" -> odtText(c).let { (t, l) ->
                        if (t.isNotBlank()) blocks += if (listLevel >= 0) DocBlock.ListItem(t, listLevel, "•", l) else DocBlock.Paragraph(t, l)
                    }
                    "text:list" -> walk(c, listLevel + 1)
                    "text:list-item", "text:section", "text:list-header" -> walk(c, listLevel)
                    "table:table" -> for (row in c.getElementsByTag("table:table-row")) {
                        val cells = row.getElementsByTag("table:table-cell").map { odtText(it).first }
                        if (cells.any { it.isNotEmpty() }) blocks += DocBlock.TableRow(cells, header = row.parent()?.tagName() == "table:table-header-rows")
                    }
                }
            }
        }
        walk(body, -1)
        Document(title, blocks)
    }

    private fun odtText(el: Element): Pair<String, List<DocLink>> {
        val out = StringBuilder()
        val links = mutableListOf<DocLink>()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> out.append(node.wholeText)
                is Element -> when (node.tagName()) {
                    "text:s", "text:tab", "text:line-break" -> out.append(' ')
                    "text:note" -> Unit
                    "text:a" -> {
                        val start = out.length
                        node.childNodes().forEach(::walk)
                        val href = node.attr("xlink:href")
                        if (href.isNotBlank() && out.length > start) links += DocLink(start, out.length, href, if (href.startsWith("#")) LinkKind.INTERNAL else LinkKind.WEB)
                    }
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }
        el.childNodes().forEach(::walk)
        val raw = out.toString()
        val lead = raw.length - raw.trimStart().length
        val text = raw.trim()
        val fixed = links.mapNotNull { l ->
            val s = (l.start - lead).coerceIn(0, text.length)
            val e = (l.end - lead).coerceIn(0, text.length)
            if (e > s) l.copy(start = s, end = e) else null
        }
        return text to Linkifier.merge(fixed, text)
    }

    // --- Livres EPUB ---

    private fun epub(file: File): Document = ZipFile(file).use { zip ->
        val container = xml(zip, "META-INF/container.xml") ?: throw DocumentException("epub")
        val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: throw DocumentException("epub")
        val opf = xml(zip, opfPath) ?: throw DocumentException("epub")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
        val blocks = mutableListOf<DocBlock>()
        var total = 0
        for (ref in opf.getElementsByTag("itemref")) {
            val href = manifest[ref.attr("idref")] ?: continue
            val entry = zip.getEntry(base + java.net.URLDecoder.decode(href, "UTF-8")) ?: zip.getEntry(base + href) ?: continue
            val chapter = zip.getInputStream(entry).use { Jsoup.parse(it, "UTF-8", "") }
            val part = html("", chapter).blocks
            blocks += part
            total += part.sumOf { it.text.length }
            if (total > MAX_CHARS) return@use Document(title, blocks, truncated = true)
        }
        Document(title, blocks)
    }
}
