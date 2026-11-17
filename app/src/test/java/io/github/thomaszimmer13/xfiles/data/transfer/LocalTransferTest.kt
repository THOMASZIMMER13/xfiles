package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalTransferTest {

    @get:Rule val tmp = TemporaryFolder()
    private val transfer = LocalTransfer()

    private fun file(relative: String, content: String = "x") =
        File(tmp.root, relative).apply { parentFile!!.mkdirs(); writeText(content) }

    private fun dir(relative: String) = File(tmp.root, relative).apply { mkdirs() }

    private fun done(r: TransferResult) = (r as TransferResult.Done).path

    @Test fun copyFileAndFolder() {
        val f = file("src/Facture.pdf", "12345")
        file("src/Projet/a.txt", "a")
        val dest = dir("dest")
        assertEquals(File(dest, "Facture.pdf").path, done(transfer.transfer(f.path, dest.path, TransferMode.COPY, null)))
        transfer.transfer(File(tmp.root, "src/Projet").path, dest.path, TransferMode.COPY, null)
        assertTrue(f.exists())
        assertEquals("12345", File(dest, "Facture.pdf").readText())
        assertEquals("a", File(dest, "Projet/a.txt").readText())
    }

    @Test fun copyInSameFolderDuplicates() {
        val f = file("src/Facture.pdf")
        assertTrue(transfer.conflicts(listOf(f.path), f.parent).isEmpty())
        assertEquals(File(f.parentFile, "Facture 2.pdf").path, done(transfer.transfer(f.path, f.parent, TransferMode.COPY, null)))
    }

    @Test fun move() {
        val f = file("src/Facture.pdf")
        val dest = dir("dest")
        transfer.transfer(f.path, dest.path, TransferMode.MOVE, null)
        assertFalse(f.exists())
        assertTrue(File(dest, "Facture.pdf").exists())
        assertEquals(TransferResult.AlreadyHere, transfer.transfer(File(dest, "Facture.pdf").path, dest.path, TransferMode.MOVE, null))
    }

    @Test fun conflicts() {
        val f = file("src/Facture.pdf", "nouveau")
        file("dest/facture.PDF", "ancien")
        val dest = File(tmp.root, "dest")
        assertEquals(setOf(f.path), transfer.conflicts(listOf(f.path), dest.path))

        assertEquals(TransferResult.Skipped, transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.SKIP))
        assertEquals(File(dest, "Facture 2.pdf").path, done(transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.KEEP_BOTH)))
        transfer.transfer(f.path, dest.path, TransferMode.COPY, ConflictChoice.REPLACE)
        assertEquals("nouveau", File(dest, "Facture.pdf").readText())
        assertFalse(dest.list()!!.contains("facture.PDF"))
    }

    @Test(expected = FileOperationException::class)
    fun conflictWithoutChoiceFails() {
        val f = file("src/a.txt")
        file("dest/a.txt")
        transfer.transfer(f.path, File(tmp.root, "dest").path, TransferMode.COPY, null)
    }

    @Test fun folderIntoItselfIsRefused() {
        val d = dir("Projet/sous")
        val reason = runCatching { transfer.transfer(d.parent, d.path, TransferMode.MOVE, null) }
            .exceptionOrNull() as FileOperationException
        assertEquals(FileOperationException.Reason.INTO_ITSELF, reason.reason)
    }
}
