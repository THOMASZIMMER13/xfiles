package io.github.thomaszimmer13.xfiles.data.fs

import io.github.thomaszimmer13.xfiles.data.shizuku.FileService
import io.github.thomaszimmer13.xfiles.data.source.LocalFileSource
import io.github.thomaszimmer13.xfiles.data.transfer.LocalTransfer
import io.github.thomaszimmer13.xfiles.data.transfer.TransferMode
import io.github.thomaszimmer13.xfiles.data.transfer.sizeOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Audit N-01, N-03, N-11 : suppressions et parcours qui ne suivent jamais un lien symbolique. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SafeFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(at: File, to: File) { Files.createSymbolicLink(at.toPath(), to.toPath()) }

    /** Dossier extérieur précieux et dossier à supprimer qui pointe dessus. */
    private fun trap(name: String): Pair<File, File> {
        val outside = File(tmp.root, "$name-DCIM").apply { mkdirs() }
        File(outside, "photo.jpg").writeText("photo")
        val single = File(tmp.root, "$name-releve.pdf").apply { writeText("releve") }
        val folder = File(tmp.root, "$name-a-supprimer/sous").apply { mkdirs() }.parentFile!!
        File(folder, "sous/note.txt").writeText("x")
        link(File(folder, "sous/vers-dcim"), outside)
        link(File(folder, "vers-fichier"), single)
        link(File(folder, "casse"), File(tmp.root, "n-existe-pas"))
        return outside to folder
    }

    private fun assertIntact(name: String, outside: File) {
        assertEquals("photo", File(outside, "photo.jpg").readText())
        assertEquals("releve", File(tmp.root, "$name-releve.pdf").readText())
    }

    @Test fun linksAreRemovedNotFollowed() {
        val (outside, folder) = trap("t")
        assertTrue(deleteRecursivelyNoFollow(folder))
        assertFalse(folder.exists())
        assertIntact("t", outside)
        // Un lien supprimé directement : le lien disparaît, pas sa cible.
        val alone = File(tmp.root, "lien-seul").also { link(it, outside) }
        assertTrue(deleteRecursivelyNoFollow(alone))
        assertFalse(Files.exists(alone.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertIntact("t", outside)
        assertTrue(deleteRecursivelyNoFollow(File(tmp.root, "absent")))
    }

    @Test fun boundaryIsEnforced() {
        val keep = File(tmp.root, "garde").apply { mkdirs() }
        val other = File(tmp.root, "autre/x").apply { mkdirs() }
        assertFalse(deleteRecursivelyNoFollow(other, keep))
        assertTrue(other.exists())
        // Préfixe commun sans séparateur : « garde-moi » n'est pas dans « garde ».
        val sibling = File(tmp.root, "garde-moi").apply { mkdirs() }
        assertFalse(deleteRecursivelyNoFollow(sibling, keep))
        assertTrue(sibling.exists())
        val inside = File(keep, "a/b").apply { mkdirs() }
        assertTrue(deleteRecursivelyNoFollow(inside.parentFile!!, keep))
    }

    @Test fun localSourceDeleteKeepsLinkTargets() = runBlocking {
        val (outside, folder) = trap("src")
        LocalFileSource().delete(folder.path)
        assertFalse(folder.exists())
        assertIntact("src", outside)
    }

    @Test fun shizukuServiceDeleteKeepsLinkTargets() {
        val android = File(tmp.root, "storage/emulated/0/Android").apply { File(this, "data").mkdirs() }
        val dcim = File(tmp.root, "storage/emulated/0/DCIM").apply { mkdirs() }
        File(dcim, "photo.jpg").writeText("photo")
        val evil = File(android, "data/com.malveillant/files").apply { mkdirs() }
        link(File(evil, "cache"), dcim)
        FileService(android.path).delete(File(android, "data/com.malveillant").path)
        assertFalse(File(android, "data/com.malveillant").exists())
        assertEquals("photo", File(dcim, "photo.jpg").readText())
    }

    @Test fun walksIgnoreLoopsAndLinks() {
        val loop = File(tmp.root, "boucle").apply { mkdirs() }
        File(loop, "f.txt").writeText("12345")
        link(File(loop, "moi"), loop)
        link(File(loop, "ailleurs"), File(tmp.root, "t-DCIM").apply { mkdirs(); File(this, "gros.bin").writeBytes(ByteArray(1000)) })
        assertEquals(listOf("f.txt"), loop.walkNoLinks().filter { it.isFile }.map { it.name }.toList())
        assertEquals(5, sizeOf(loop))
        // Copie : un seul fichier, aucun lien suivi ni recopié.
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        LocalTransfer().transfer(loop.path, dest.path, TransferMode.COPY, null)
        assertEquals(listOf("f.txt"), File(dest, "boucle").walkTopDown().filter { it.isFile }.map { it.name }.toList())
        assertEquals(setOf("f.txt"), File(dest, "boucle").list()!!.toSet())
    }

    @Test fun moveAcrossVolumesKeepsLinkTargets() {
        // Déplacement dont le renommage échoue (autre volume simulé) : copie puis suppression.
        val (outside, folder) = trap("mv")
        val dest = File(tmp.root, "dest-mv").apply { mkdirs() }
        LocalTransfer(journal = null, rename = { _, _ -> false }).transfer(folder.path, dest.path, TransferMode.MOVE, null)
        assertFalse(folder.exists())
        assertIntact("mv", outside)
        assertTrue(File(dest, folder.name + "/sous/note.txt").exists())
    }
}
