package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lecture et extraction par libarchive (audit H-06, F-01), avec les archives de référence
 * du jeu de tests de libarchive (dossier app/native/third_party, propriété xfiles.libarchive.tests).
 */
class LibArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var samples: File

    @Before fun setUp() {
        val dir = System.getProperty("xfiles.libarchive.tests")?.let(::File)
        assumeTrue("jeu de tests libarchive absent", dir != null && dir.isDirectory)
        assumeTrue("libarchive non chargée", LibArchive.available)
        samples = dir!!
    }

    /** Décode un fichier uuencodé de libarchive vers [name] dans le dossier temporaire. */
    private fun sample(uu: String, name: String): File {
        val out = ByteArrayOutputStream()
        File(samples, uu).readLines().dropWhile { !it.startsWith("begin ") }.drop(1).forEach { line ->
            if (line == "end" || line.isEmpty()) return@forEach
            val n = (line[0].code - 32) and 63
            if (n == 0) return@forEach
            val bytes = ByteArray(n)
            var k = 0
            var i = 1
            while (k < n) {
                fun c(j: Int) = if (i + j < line.length) (line[i + j].code - 32) and 63 else 0
                val v = (c(0) shl 18) or (c(1) shl 12) or (c(2) shl 6) or c(3)
                for (s in intArrayOf(16, 8, 0)) if (k < n) bytes[k++] = (v shr s).toByte()
                i += 4
            }
            out.write(bytes)
        }
        return File(tmp.root, name).apply { writeBytes(out.toByteArray()) }
    }

    @Test fun rar4ListsAndExtracts() {
        val rar = sample("test_read_format_rar.rar.uu", "test.rar")
        val entries = Archives.list(rar.path)
        val text = entries.single { it.path == "test.txt" }
        assertEquals(20, text.size)
        assertTrue(entries.any { it.path == "testdir" && it.isDirectory })
        // Le lien symbolique n'est jamais montré ni extrait.
        assertFalse(entries.any { it.path == "testlink" })

        val out = tmp.newFolder("out4")
        val top = File(Archives.extract(rar.path, null, out))
        assertEquals("test text document\r\n", File(top, "test.txt").readText())
        assertEquals("test text document\r\n", File(top, "testdir/test.txt").readText())
        assertFalse(File(top, "testlink").exists())

        // Extraction d'un seul élément.
        val single = tmp.newFolder("seul")
        val one = File(Archives.extract(rar.path, "testdir/test.txt", single))
        assertEquals("test.txt", one.name)
        assertEquals(20, one.length())
    }

    @Test fun rar5Extracts() {
        val rar = sample("test_read_format_rar5_compressed.rar.uu", "test5.rar")
        val entry = Archives.list(rar.path).single()
        assertEquals("test.bin", entry.path)
        assertEquals(1200, entry.size)
        assertTrue(entry.time > 0)
        val top = File(Archives.extract(rar.path, null, tmp.newFolder("out5")))
        assertEquals(1200, File(top, "test.bin").length())
    }

    @Test fun encryptedRarIsDetected() {
        val rar = sample("test_read_format_rar4_encrypted.rar.uu", "secret.rar")
        assertTrue(Archives.list(rar.path).isNotEmpty())
        val out = tmp.newFolder("chiffre")
        try {
            Archives.extract(rar.path, null, out)
            fail("RAR chiffré extrait")
        } catch (e: FileOperationException) {
            assertEquals(Reason.ENCRYPTED_UNSUPPORTED, e.reason)
        }
        // L'extraction ratée ne laisse rien derrière elle.
        assertEquals(0, out.list()!!.size)
    }

    @Test fun isoWithHardLink() {
        val z = sample("test_read_format_iso_joliet_rockridge.iso.Z.uu", "image.iso.Z")
        val iso = File(tmp.root, "image.iso")
        ZCompressorInputStream(z.inputStream().buffered()).use { input -> iso.outputStream().use { input.copyTo(it) } }
        assertEquals(ArchiveFormat.ISO, archiveFormatOf(iso.name))
        val entries = Archives.list(iso.path)
        assertTrue(entries.any { it.path == "dir" && it.isDirectory })
        assertTrue(entries.any { it.path == "long-joliet-file-name.textfile" })
        assertFalse(entries.any { it.path == "symlink" })
        val top = File(Archives.extract(iso.path, null, tmp.newFolder("iso")))
        assertEquals(6, File(top, "hardlink").length())
        assertFalse(File(top, "symlink").exists())
    }

    /** Tar piégé lu par le même code natif : noms bruts remontés, liens marqués « autres ». */
    @Test fun trapNamesComeThroughRaw() {
        val tar = File(tmp.root, "piege.tar")
        TarArchiveOutputStream(tar.outputStream()).use { t ->
            fun add(name: String, body: String) {
                val bytes = body.toByteArray()
                t.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                t.write(bytes)
                t.closeArchiveEntry()
            }
            add("../evade.txt", "dehors")
            add("ok.txt", "dedans")
            t.putArchiveEntry(TarArchiveEntry("lien", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" })
            t.closeArchiveEntry()
        }
        val seen = mutableMapOf<String, Int>()
        LibArchive.read(tar.path, null, LibArchive.TAR) { e, input ->
            seen[e.name] = e.type
            if (e.name == "ok.txt") assertEquals("dedans", input.readBytes().decodeToString())
        }
        assertEquals(LibArchive.TYPE_FILE, seen["../evade.txt"])
        assertEquals(LibArchive.TYPE_OTHER, seen["lien"])
        assertEquals(LibArchive.TYPE_FILE, seen["ok.txt"])
    }

    @Test fun insideNeedsTheSeparator() {
        val root = tmp.newFolder("Photos")
        assertTrue(Archives.isInside(root, File(root, "a/b.jpg")))
        assertTrue(Archives.isInside(root, root))
        // Même préfixe sans séparateur : « Photos-secret » n'est pas dans « Photos ».
        assertFalse(Archives.isInside(root, File(tmp.root, "Photos-secret/x")))
        assertFalse(Archives.isInside(root, File(root, "../x")))
    }

    @Test fun zipSlipStaysInside() {
        val tar = File(tmp.root, "slip.tar")
        TarArchiveOutputStream(tar.outputStream()).use { t ->
            for (name in listOf("../../evade.txt", "/abs.txt", "dossier/../../x.txt", "bon.txt")) {
                t.putArchiveEntry(TarArchiveEntry(name).apply { size = 1 })
                t.write('x'.code)
                t.closeArchiveEntry()
            }
        }
        val out = tmp.newFolder("cible")
        val top = File(Archives.extract(tar.path, null, out))
        assertTrue(File(top, "bon.txt").isFile)
        assertFalse(File(tmp.root, "evade.txt").exists())
        assertFalse(File(tmp.root, "x.txt").exists())
        assertEquals(listOf(top.name), out.list()!!.toList())
    }
}
