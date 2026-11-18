package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Garde d'extraction (audit F-02). */
class ExtractGuardTest {

    private val gib = 1L shl 30

    @Test fun planUnknownSizeIsUnbounded() {
        val p = ExtractGuard.plan(0, 1000, 10 * gib)
        assertFalse(p.noSpace)
        assertFalse(p.suspicious)
        assertEquals(Long.MAX_VALUE, p.maxBytes)
    }

    @Test fun planDetectsMissingSpace() {
        assertTrue(ExtractGuard.plan(gib, gib / 2, gib).noSpace)
        assertFalse(ExtractGuard.plan(100L shl 20, 50L shl 20, gib).noSpace)
    }

    @Test fun planFlagsBombs() {
        // 10 Gio annoncés pour 10 Mo d'archive : taux de 1000.
        assertTrue(ExtractGuard.plan(10 * gib, 10L shl 20, 100 * gib).suspicious)
        // Grosse archive peu compressée : rien à signaler.
        assertFalse(ExtractGuard.plan(2 * gib, gib, 100 * gib).suspicious)
        // Fort taux mais petite taille : rien à signaler.
        assertFalse(ExtractGuard.plan(500L shl 20, 1L shl 20, 100 * gib).suspicious)
    }

    @Test fun guardStopsBeyondCeiling() {
        val guard = ExtractGuard(File("/tmp"), maxBytes = 1000, freeSpace = { Long.MAX_VALUE })
        guard.onBytes(900)
        try {
            guard.onBytes(200)
            fail("plafond dépassé sans erreur")
        } catch (e: FileOperationException) {
            assertEquals(Reason.TOO_BIG, e.reason)
        }
    }

    @Test fun guardStopsWhenDiskFills() {
        var free = 10 * gib
        val guard = ExtractGuard(File("/tmp"), freeSpace = { free })
        guard.onBytes(9L shl 20)
        free = ExtractGuard.RESERVE - 1
        try {
            guard.onBytes(9L shl 20)
            fail("disque plein sans erreur")
        } catch (e: FileOperationException) {
            assertEquals(Reason.NO_SPACE, e.reason)
        }
    }
}
