package io.github.thomaszimmer13.xfiles.data.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, String>): File = File(tmp.root, name).apply {
        ZipOutputStream(outputStream()).use { z ->
            entries.forEach { (path, content) ->
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test fun linksPhonesEmails() {
        val text = "Appelez le 06 12 34 56 78 ou +33 1 23 45 67 89, écrivez à jean.dupont@exemple.fr ou visitez www.exemple.fr/page."
        val links = Linkifier.find(text)
        assertEquals(listOf(LinkKind.PHONE, LinkKind.PHONE, LinkKind.EMAIL, LinkKind.WEB), links.map { it.kind })
        assertEquals("tel:0612345678", links[0].target)
        assertEquals("tel:+33123456789", links[1].target)
        assertEquals("mailto:jean.dupont@exemple.fr", links[2].target)
        assertEquals("https://www.exemple.fr/page", links[3].target)
        assertEquals("www.exemple.fr/page", text.substring(links[3].start, links[3].end))
        assertTrue(Linkifier.find("Version 2026.11.17, code 12345").isEmpty())
    }

    @Test fun markdown() {
        val f = File(tmp.root, "notes.md").apply {
            writeText("# Titre\n\nUn **paragraphe** sur\ndeux lignes avec [un lien](https://ex.fr).\n\n## Liste\n- un\n  - sous\n1. premier\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```\ncode ici\n```\n")
        }
        val doc = DocumentReader.read(f)
        val b = doc.blocks
        assertEquals(DocBlock.Heading(1, "Titre"), b[0])
        val p = b[1] as DocBlock.Paragraph
        assertEquals("Un paragraphe sur deux lignes avec un lien.", p.text)
        assertEquals("un lien", p.text.substring(p.links[0].start, p.links[0].end))
        assertEquals("https://ex.fr", p.links[0].target)
        assertEquals(DocBlock.Heading(2, "Liste"), b[2])
        assertEquals(1, (b[4] as DocBlock.ListItem).level)
        assertEquals("1.", (b[5] as DocBlock.ListItem).marker)
        assertEquals(listOf("A", "B"), (b[6] as DocBlock.TableRow).cells)
        assertEquals(listOf("1", "2"), (b[7] as DocBlock.TableRow).cells)
        assertEquals(DocBlock.Preformatted("code ici"), b[8])
        assertTrue(doc.editable)
    }

    @Test fun htmlPage() {
        val f = File(tmp.root, "page.html").apply {
            writeText(
                "<html><head><title>Ma page</title><script>x()</script></head><body><h1>Bienvenue</h1>" +
                    "<p>Texte <a href=\"https://a.fr\">ici</a> et <b>gras</b>.</p><ul><li>Un<ul><li>Deux</li></ul></li></ul>" +
                    "<table><tr><th>Nom</th><th>Tél</th></tr><tr><td>Bob</td><td>01 23 45 67 89</td></tr></table><div>Fin <img alt=\"logo\"></div></body></html>",
            )
        }
        val doc = DocumentReader.read(f)
        assertEquals("Ma page", doc.title)
        val b = doc.blocks
        assertEquals(DocBlock.Heading(1, "Bienvenue"), b[0])
        val p = b[1] as DocBlock.Paragraph
        assertEquals("Texte ici et gras.", p.text)
        assertEquals("ici", p.text.substring(p.links[0].start, p.links[0].end))
        assertEquals("Un", (b[2] as DocBlock.ListItem).text)
        assertEquals(1, (b[3] as DocBlock.ListItem).level)
        assertTrue((b[4] as DocBlock.TableRow).header)
        assertEquals("Fin Image : logo", b[6].text)
    }

    @Test fun csvWithQuotes() {
        val f = File(tmp.root, "t.csv").apply { writeText("nom;ville\n\"Dupont; Jean\";Lyon\n") }
        val rows = DocumentReader.read(f).blocks.map { (it as DocBlock.TableRow).cells }
        assertEquals(listOf(listOf("nom", "ville"), listOf("Dupont; Jean", "Lyon")), rows)
    }

    @Test fun latin1Text() {
        val f = File(tmp.root, "vieux.txt").apply { writeBytes("Café crème".toByteArray(charset("windows-1252"))) }
        assertEquals("Café crème", DocumentReader.read(f).blocks.single().text)
    }

    @Test fun docx() {
        val document = """<?xml version="1.0"?><w:document xmlns:w="w" xmlns:r="r"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Titre1"/></w:pPr><w:r><w:t>Chapitre</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Voir </w:t></w:r><w:hyperlink r:id="rId5"><w:r><w:t>le site</w:t></w:r></w:hyperlink><w:r><w:t>.</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="1"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Puce</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val styles = """<w:styles xmlns:w="w"><w:style w:styleId="Titre1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val rels = """<Relationships><Relationship Id="rId5" Target="https://ex.fr"/></Relationships>"""
        val f = zip("doc.docx", mapOf("word/document.xml" to document, "word/styles.xml" to styles, "word/_rels/document.xml.rels" to rels))
        val b = DocumentReader.read(f).blocks
        assertEquals(DocBlock.Heading(1, "Chapitre"), b[0])
        val p = b[1] as DocBlock.Paragraph
        assertEquals("Voir le site.", p.text)
        assertEquals("le site", p.text.substring(p.links[0].start, p.links[0].end))
        assertEquals(DocBlock.ListItem("Puce", 1, "•"), b[2])
        assertEquals(listOf("A", "B"), (b[3] as DocBlock.TableRow).cells)
    }

    @Test fun odt() {
        val content = """<office:document-content xmlns:office="o" xmlns:text="t" xmlns:xlink="x"><office:body><office:text>
            <text:h text:outline-level="2">Partie</text:h>
            <text:p>Bonjour<text:s/><text:a xlink:href="https://ex.fr">ici</text:a></text:p>
            <text:list><text:list-item><text:p>Élément</text:p></text:list-item></text:list>
            </office:text></office:body></office:document-content>"""
        val b = DocumentReader.read(zip("d.odt", mapOf("content.xml" to content))).blocks
        assertEquals(DocBlock.Heading(2, "Partie"), b[0])
        assertEquals("Bonjour ici", b[1].text)
        assertEquals(DocBlock.ListItem("Élément", 0, "•"), b[2])
    }

    @Test fun epub() {
        val f = zip(
            "livre.epub",
            mapOf(
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                "OEBPS/content.opf" to """<package><metadata><dc:title>Mon livre</dc:title></metadata><manifest><item id="c1" href="c1.xhtml"/><item id="c2" href="c2.xhtml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
                "OEBPS/c1.xhtml" to "<html><body><h1>Un</h1><p>Début.</p></body></html>",
                "OEBPS/c2.xhtml" to "<html><body><h1>Deux</h1><p>Suite.</p></body></html>",
            ),
        )
        val doc = DocumentReader.read(f)
        assertEquals("Mon livre", doc.title)
        assertEquals(listOf("Un", "Début.", "Deux", "Suite."), doc.blocks.map { it.text })
    }

    @Test fun editorKeepsFormat() {
        val crlfBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "un\r\ndeux\r\n".toByteArray()
        val e = TextFileCodec.decode(crlfBom)
        assertEquals("un\ndeux\n", e.text)
        assertEquals(TextFormat(Charsets.UTF_8, true, "\r\n"), e.format)
        assertTrue(TextFileCodec.encode(e.text, e.format).first.contentEquals(crlfBom))

        val latin = "Café\n".toByteArray(charset("windows-1252"))
        val l = TextFileCodec.decode(latin)
        assertEquals("Café\n", l.text)
        assertTrue(TextFileCodec.encode(l.text, l.format).first.contentEquals(latin))
        // Un caractère absent de Windows-1252 : passage en UTF-8.
        val (bytes, changed) = TextFileCodec.encode("Café ✓\n", l.format)
        assertTrue(changed)
        assertEquals("Café ✓\n", String(bytes, Charsets.UTF_8))

        val f = File(tmp.root, "a.txt").apply { writeText("ancien") }
        TextFileCodec.writeAtomically(f, "nouveau".toByteArray())
        assertEquals("nouveau", f.readText())
        assertEquals(listOf("a.txt"), tmp.root.list()!!.filter { it.endsWith(".txt") || it.contains("tmp") }.sorted())
    }

    @Test fun pdfParagraphs() {
        val text = "Première ligne d'un para-\ngraphe coupé\nqui continue.\n\nSecond paragraphe.\n Troisième."
        assertEquals(
            listOf("Première ligne d'un paragraphe coupé qui continue.", "Second paragraphe.", "Troisième."),
            PdfText.paragraphs(text),
        )
    }
}
