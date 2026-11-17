package io.github.thomaszimmer13.xfiles.data.media

import io.github.thomaszimmer13.xfiles.a11y.TimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaylistsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(rel: String) = File(tmp.root, rel).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun m3uWithRelativeAbsoluteMissingAndUrls() {
        val a = file("Musique/Rock/a.mp3")
        val b = file("Musique/b.flac")
        val list = File(tmp.root, "Musique/Rock/liste.m3u").apply {
            writeText(
                "#EXTM3U\n#EXTINF:215,Groupe - Titre A\na.mp3\n\n#EXTINF:-1,B\n${b.path}\nabsent.mp3\n" +
                    "#EXTINF:-1,Radio\nhttps://radio.example/flux\n",
            )
        }
        val parsed = Playlists.parse(list) as ParsedPlaylist.Entries
        assertEquals(1, parsed.missing)
        assertEquals(listOf(a.canonicalPath, b.canonicalPath, "https://radio.example/flux"), parsed.entries.map { it.location })
        assertEquals("Groupe - Titre A", parsed.entries[0].title)
        assertEquals(215L, parsed.entries[0].durationSeconds)
        assertTrue(parsed.entries[2].isRemote)
    }

    @Test fun plsAndHls() {
        file("x/un.mp3")
        val pls = File(tmp.root, "x/l.pls").apply {
            writeText("[playlist]\nFile1=un.mp3\nTitle1=Un\nLength1=60\nFile2=http://r.example/s\nNumberOfEntries=2\n")
        }
        val parsed = Playlists.parse(pls) as ParsedPlaylist.Entries
        assertEquals(listOf("Un", null), parsed.entries.map { it.title })
        val hls = File(tmp.root, "live.m3u8").apply { writeText("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n") }
        assertEquals(ParsedPlaylist.Stream, Playlists.parse(hls))
    }

    @Test fun writeAndReadBack() {
        val a = file("M/Album/01.mp3")
        val b = file("M/autre.ogg")
        val list = File(tmp.root, "M/Album/ma liste.m3u8")
        list.writeText(Playlists.toM3u(listOf(PlaylistEntry(a.path, "Piste 1", 100), PlaylistEntry(b.path)), list.parentFile!!))
        assertTrue(list.readText().contains("\n01.mp3\n"))
        assertTrue(list.readText().contains("\n../autre.ogg\n"))
        Playlists.append(list, listOf(PlaylistEntry("https://r.example/s", "Radio")))
        val back = Playlists.parse(list) as ParsedPlaylist.Entries
        assertEquals(listOf(a.canonicalPath, b.canonicalPath, "https://r.example/s"), back.entries.map { it.location })
        assertEquals("Piste 1", back.entries[0].title)
    }

    @Test fun mediaKindsAndSubtitles() {
        assertTrue(MediaKinds.isAudio("Chanson.FLAC"))
        assertTrue(MediaKinds.isVideo("film.mkv"))
        assertTrue(MediaKinds.isPlaylist("x.m3u8"))
        val video = file("v/Film.mkv")
        file("v/Film.fr.srt"); file("v/Film.srt"); file("v/Autre.srt")
        assertEquals(listOf("Film.fr.srt", "Film.srt"), MediaKinds.sidecarSubtitles(video).map { it.name })
        assertEquals("fr", MediaKinds.subtitleLanguage(video, File(video.parentFile, "Film.fr.srt")))
        assertNull(MediaKinds.subtitleLanguage(video, File(video.parentFile, "Film.srt")))
    }

    @Test fun times() {
        assertEquals("4:10", TimeFormatter.short(250_000))
        assertEquals("1:02:05", TimeFormatter.short(3_725_000))
        assertEquals("4 minutes 10 secondes", TimeFormatter.spoken(250_000))
        assertEquals("1 heure 2 minutes", TimeFormatter.spoken(3_725_000))
        assertEquals("1 minute 1 seconde", TimeFormatter.spoken(61_000))
        assertEquals(83_000L, TimeFormatter.parse("1:23"))
        assertEquals(3_725_000L, TimeFormatter.parse("1:02:05"))
        assertNull(TimeFormatter.parse("abc"))
    }
}
