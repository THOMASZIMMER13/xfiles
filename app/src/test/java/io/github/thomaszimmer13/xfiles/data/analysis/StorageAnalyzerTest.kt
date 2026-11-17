package io.github.thomaszimmer13.xfiles.data.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class StorageAnalyzerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(relative: String, bytes: ByteArray, ageDays: Long = 0): File =
        File(tmp.root, relative).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
            setLastModified(NOW - TimeUnit.DAYS.toMillis(ageDays))
        }

    @Test fun findsDuplicatesOldDownloadsAndFolders() = runBlocking {
        val content = Random(1).nextBytes(200 * 1024)
        val sameStart = content.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        file("Photos/a.jpg", content, ageDays = 10)
        file("Documents/copie de a.jpg", content, ageDays = 2)
        file("Documents/presque.jpg", sameStart) // mêmes 64 premiers ko, contenu différent
        file("Download/vieux.pdf", ByteArray(10), ageDays = 200)
        file("Download/recent.pdf", ByteArray(10), ageDays = 5)
        file(".cache/cache.bin", content) // dossier caché : ignoré
        file(".xfiles-corbeille/files/a.jpg", content)

        val analyzer = StorageAnalyzer { listOf(tmp.root.path) }
        val result = analyzer.analyze(listOf(tmp.root.path), File(tmp.root, "Download").path, NOW)

        assertEquals(5, result.scannedFiles)
        assertEquals(1, result.duplicates.size)
        val group = result.duplicates.single()
        assertEquals(listOf("a.jpg", "copie de a.jpg"), group.files.map { it.name }) // plus ancien d'abord
        assertEquals(200L * 1024, group.wasted)
        assertEquals(listOf("vieux.pdf"), result.oldDownloads.map { it.name })
        assertEquals("Documents", result.heaviestFolders.first().name)
        assertTrue(result.bigFiles.isEmpty())
    }

    @Test fun hashesAndFolderSize() {
        val f = file("x/hello.txt", "hello".toByteArray())
        file("x/sous/b.txt", ByteArray(3))
        val (md5, sha) = FileStats.hashes(f)
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha)
        assertEquals(FolderSize(8, 2, 1), FileStats.folderSize(File(tmp.root, "x")))
    }

    private companion object {
        val NOW = TimeUnit.DAYS.toMillis(20_000)
    }
}
