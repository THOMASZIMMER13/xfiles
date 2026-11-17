package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchivesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(relative: String, content: String = "x") =
        File(tmp.root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    private fun sources(): List<File> {
        file("src/Projet/a.txt", "a")
        file("src/Projet/sous/b.txt", "bb")
        return listOf(File(tmp.root, "src/Projet"), file("src/Facture.pdf", "pdf"))
    }

    @Test fun formatDetection() {
        assertEquals(ArchiveFormat.TAR_GZ, archiveFormatOf("Photos.tar.gz"))
        assertEquals(ArchiveFormat.GZ, archiveFormatOf("notes.txt.gz"))
        assertEquals(ArchiveFormat.SEVEN_Z, archiveFormatOf("X.7Z"))
        assertEquals(ArchiveFormat.RAR, archiveFormatOf("film.rar"))
        assertNull(archiveFormatOf("photo.jpg"))
        assertEquals("Photos", ArchiveFormat.TAR_GZ.baseName("Photos.tar.gz"))
        assertEquals("Projet 2.tar.gz", Archives.uniqueArchiveName("Projet.tar.gz", listOf("projet.tar.gz"), CreateFormat.TAR_GZ))
    }

    private fun roundTrip(format: CreateFormat, password: String? = null) {
        val archive = File(tmp.root, "Archive${format.extension}")
        Archives.compress(format, sources(), archive, password = password)
        assertEquals(password != null, Archives.needsPassword(archive.path))

        val entries = Archives.list(archive.path, password)
        assertEquals(setOf("Projet", "Facture.pdf"), ZipArchive.children(entries, "").map { it.name }.toSet())
        assertEquals(6L, Archives.uncompressedSize(entries, null))

        val out = File(tmp.root, "out-${format.name}").apply { mkdirs() }
        val all = File(Archives.extract(archive.path, null, out, password = password))
        assertEquals("Archive", all.name)
        assertEquals("bb", File(all, "Projet/sous/b.txt").readText())
        assertEquals("pdf", File(all, "Facture.pdf").readText())

        val sub = File(Archives.extract(archive.path, "Projet", out, password = password))
        assertEquals("Projet", sub.name)
        assertEquals("a", File(sub, "a.txt").readText())
        assertEquals("Projet 2", File(Archives.extract(archive.path, "Projet", out, password = password)).name)
    }

    @Test fun tarGzRoundTrip() = roundTrip(CreateFormat.TAR_GZ)

    @Test fun sevenZRoundTrip() = roundTrip(CreateFormat.SEVEN_Z)

    @Test fun encryptedZipRoundTrip() = roundTrip(CreateFormat.ZIP, password = "secret")

    @Test fun wrongOrMissingPassword() {
        val archive = File(tmp.root, "Secret.zip")
        Archives.compress(CreateFormat.ZIP, sources(), archive, password = "secret")
        Archives.checkPassword(archive.path, "secret")
        expectReason(FileOperationException.Reason.WRONG_PASSWORD) { Archives.checkPassword(archive.path, "faux") }
        val out = File(tmp.root, "out").apply { mkdirs() }
        expectReason(FileOperationException.Reason.ENCRYPTED) { Archives.extract(archive.path, null, out, password = null) }
        assertTrue("rien ne doit rester après un échec", out.list().isNullOrEmpty())
    }

    @Test fun singleGzFile() {
        val gz = File(tmp.root, "notes.txt.gz")
        GzipCompressorOutputStream(gz.outputStream()).use { it.write("bonjour".toByteArray()) }
        assertEquals(listOf("notes.txt"), Archives.list(gz.path).map { it.path })
        val out = File(tmp.root, "out").apply { mkdirs() }
        val made = File(Archives.extract(gz.path, null, out))
        assertEquals("notes.txt", made.name)
        assertEquals("bonjour", made.readText())
        assertFalse(Archives.needsPassword(gz.path))
    }

    @Test fun brokenArchive() {
        val bad = File(tmp.root, "abime.7z").apply { writeText("pas une archive") }
        expectReason(FileOperationException.Reason.BAD_ARCHIVE) { Archives.list(bad.path) }
    }

    private fun expectReason(reason: FileOperationException.Reason, block: () -> Unit) {
        try {
            block()
            fail("$reason attendu")
        } catch (e: FileOperationException) {
            assertEquals(reason, e.reason)
        }
    }
}
