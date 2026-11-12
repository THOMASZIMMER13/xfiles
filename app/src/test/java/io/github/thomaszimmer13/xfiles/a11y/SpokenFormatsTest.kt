package io.github.thomaszimmer13.xfiles.a11y

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Vérifie les textes lus par TalkBack, pour éviter toute régression silencieuse. */
class SpokenFormatsTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 11, 12, 18, 0, 0, 0, zone)
    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun sizes() {
        assertEquals("0 octet", SizeFormatter.spoken(0))
        assertEquals("1,5 kilooctet", SizeFormatter.spoken(1_500))
        assertEquals("245 kilooctets", SizeFormatter.spoken(245_000))
        assertEquals("34,2 gigaoctets", SizeFormatter.spoken(34_200_000_000))
        assertEquals("128 Go", SizeFormatter.short(128_000_000_000))
    }

    @Test fun dates() {
        assertEquals("modifié aujourd'hui à 9 h 05", DateFormatter.spoken(at(2026, 11, 12, 9, 5), now))
        assertEquals("modifié hier à 18 h 30", DateFormatter.spoken(at(2026, 11, 11, 18, 30), now))
        assertEquals("modifié le 1er mars à 14 h 00", DateFormatter.spoken(at(2026, 3, 1, 14), now))
        assertEquals("modifié le 3 mars 2024", DateFormatter.spoken(at(2024, 3, 3), now))
        assertEquals("Aujourd'hui 09:05", DateFormatter.short(at(2026, 11, 12, 9, 5), now))
        assertEquals("3 mars 2024", DateFormatter.short(at(2024, 3, 3), now))
    }

    @Test fun types() {
        assertEquals("dossier", FileTypeDescriber.describe("Factures", isDirectory = true))
        assertEquals("document PDF", FileTypeDescriber.describe("Facture.PDF", isDirectory = false))
        assertEquals("script Lua", FileTypeDescriber.describe("auto.lua", isDirectory = false))
        assertEquals("fichier", FileTypeDescriber.describe("README", isDirectory = false))
    }

    @Test fun itemCounts() {
        assertEquals("vide", FileDescriptions.itemCount(0))
        assertEquals("1 élément", FileDescriptions.itemCount(1))
        assertEquals("12 éléments", FileDescriptions.itemCount(12))
    }

    @Test fun folderRowOrder() {
        val folder = FileItem("/s/Factures", "Factures", true, 0, at(2026, 11, 12, 9, 5), 12)
        // L'ordre exigé : nom, type, taille, date.
        assertEquals("Factures, dossier, 12 éléments, modifié aujourd'hui à 9 h 05", FileDescriptions.spoken(folder, now))

        val file = FileItem("/s/Facture.pdf", "Facture.pdf", false, 245_000, at(2024, 3, 3), null)
        assertEquals("Facture.pdf, document PDF, 245 kilooctets, modifié le 3 mars 2024", FileDescriptions.spoken(file, now))
    }
}
