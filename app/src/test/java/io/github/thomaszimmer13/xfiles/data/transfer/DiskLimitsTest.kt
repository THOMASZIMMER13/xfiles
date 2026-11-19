package io.github.thomaszimmer13.xfiles.data.transfer

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Audit N-08 : place libre et limite FAT32 contrôlées avant un collage, erreurs distinctes. */
class DiskLimitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mounts = """
        /dev/block/dm-48 /data f2fs rw,lazytime 0 0
        /dev/fuse /storage/emulated fuse rw,nosuid 0 0
        /dev/block/vold/public:179,1 /mnt/media_rw/1A2B-3C4D vfat rw,dirsync 0 0
        /dev/fuse /storage/1A2B-3C4D fuse rw,nosuid 0 0
        /dev/block/vold/public:8,1 /mnt/media_rw/5E6F-7081 exfat rw 0 0
        /dev/fuse /storage/5E6F-7081 fuse rw 0 0
        /dev/block/sdb1 /mnt/media_rw/Ma\040cl\040USB vfat rw 0 0
    """.trimIndent()

    @Test fun fat32IsDetectedBehindFuse() {
        assertTrue(DiskLimits.isFat32("/storage/1A2B-3C4D/DCIM", mounts))
        assertTrue(DiskLimits.isFat32("/mnt/media_rw/1A2B-3C4D", mounts))
        assertFalse(DiskLimits.isFat32("/storage/5E6F-7081/Films", mounts)) // exFAT : pas de limite de 4 Go
        assertFalse(DiskLimits.isFat32("/storage/emulated/0/Download", mounts))
        assertFalse(DiskLimits.isFat32("/storage/1A2B-3C4Dbis/x", mounts))
        assertTrue(DiskLimits.isFat32("/storage/Ma cl USB/x", mounts))
    }

    /** Fichier creux : 4 Go annoncés sans les écrire. */
    private fun sparse(name: String, size: Long) = File(tmp.root, name).apply { RandomAccessFile(this, "rw").use { it.setLength(size) } }

    @Test fun distinctReasonsBeforePasting() {
        val dest = tmp.newFolder("carte")
        val film = sparse("film.mkv", DiskLimits.FAT32_MAX_FILE + 1)
        val small = File(tmp.root, "note.txt").apply { writeText("x".repeat(1000)) }
        val folder = tmp.newFolder("Vacances").also { sparse("Vacances/rush.mp4", 5L shl 30) }

        // FAT32 : fichier de plus de 4 Go, seul ou dans un dossier.
        assertEquals(Reason.FILE_TOO_LARGE_FOR_FS, DiskLimits.check(listOf(film), dest, needsSpace = true, fat32 = true, free = Long.MAX_VALUE))
        assertEquals(Reason.FILE_TOO_LARGE_FOR_FS, DiskLimits.check(listOf(folder), dest, needsSpace = true, fat32 = true, free = Long.MAX_VALUE))
        // Exactement 4 Gio moins un octet : accepté.
        val limit = sparse("limite.bin", DiskLimits.FAT32_MAX_FILE)
        assertNull(DiskLimits.check(listOf(limit), dest, needsSpace = false, fat32 = true, free = Long.MAX_VALUE))
        // Hors FAT32 : seule la place compte.
        assertNull(DiskLimits.check(listOf(film), dest, needsSpace = true, fat32 = false, free = 10L shl 30))
        assertEquals(Reason.NO_SPACE, DiskLimits.check(listOf(film, small), dest, needsSpace = true, fat32 = false, free = 1L shl 30))
        // Déplacement sur le même volume : un renommage, aucune place à trouver.
        assertNull(DiskLimits.check(listOf(film), dest, needsSpace = false, fat32 = false, free = 1))
        assertNull(DiskLimits.check(listOf(small), dest, needsSpace = true, fat32 = true, free = 10L shl 20))
    }

    @Test fun writeErrorsAreRecognised() {
        assertEquals(Reason.NO_SPACE, DiskLimits.reasonOf(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(Reason.FILE_TOO_LARGE_FOR_FS, DiskLimits.reasonOf(IOException("x", IOException("write failed: EFBIG (File too large)"))))
        assertNull(DiskLimits.reasonOf(IOException("EACCES (Permission denied)")))
    }
}
