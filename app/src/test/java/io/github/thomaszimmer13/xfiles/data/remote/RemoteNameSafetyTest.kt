package io.github.thomaszimmer13.xfiles.data.remote

import io.github.thomaszimmer13.xfiles.data.shizuku.FileService
import io.github.thomaszimmer13.xfiles.data.shizuku.IFileService
import io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuRemote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** C-01 : un nom piégé venu d'un serveur ne doit jamais permettre d'écrire hors du dossier prévu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteNameSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val traps = listOf("", ".", "..", "../x", "a/b", "../../shared_prefs/securite.xml", "x\u0000y", "/")

    @Test fun trapNamesAreRefused() {
        traps.forEach { assertNull("« $it » accepté", RemoteClient.safeName(it)) }
        listOf("Facture.pdf", ".cache", "a\\b", "Été 2026", "..txt", "...").forEach { assertEquals(it, RemoteClient.safeName(it)) }
    }

    /** Noms encodés (WebDAV décode les href) et séparateurs Windows. */
    @Test fun encodedTraversalIsRefusedAfterDecoding() {
        for (encoded in listOf("%2e%2e%2f", "%2E%2E", "..%2fsecret", "%2e%2e%5c..%2f", "a%2fb", "%00")) {
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
            val name = RemoteClient.safeName(decoded)
            if (name != null) {
                // Seuls restent les noms sans « / » : la barre oblique inverse est un caractère comme un autre.
                assertFalse(decoded, "/" in name || name == "..")
            }
        }
        assertNull(RemoteClient.safeName(java.net.URLDecoder.decode("%2e%2e%2f", "UTF-8")))
        assertNull(RemoteClient.safeName(java.net.URLDecoder.decode("a%2fb", "UTF-8")))
        // Non décodé, « %2e%2e%2f » n'est qu'un nom bizarre, qui reste dans le dossier.
        val root = File(tmp.root, "remote/srv").apply { mkdirs() }
        assertTrue(RemoteClient.inside(root, File(root, RemoteClient.safeName("%2e%2e%2f")!!)))
        // « ..\x » : sous Android, « \ » n'est pas un séparateur ; le fichier reste dans le dossier.
        assertTrue(RemoteClient.inside(root, File(root, RemoteClient.safeName("..\\x")!!)))
    }

    @Test fun insideRejectsEscapesAndSiblingPrefixes() {
        val root = File(tmp.root, "remote/srv").apply { mkdirs() }
        assertTrue(RemoteClient.inside(root, File(root, "Photos/a.jpg")))
        assertFalse(RemoteClient.inside(root, File(root, "../../files/servers.json")))
        assertFalse(RemoteClient.inside(root, File(tmp.root, "remote/srv2/a"))) // préfixe sans séparateur
    }

    @Test fun rcloneListingDropsTrapNames() {
        val rpc = RcloneRpc { _, _ ->
            val list = JSONArray()
            (traps + "ok.txt").forEach { list.put(JSONObject().put("Name", it).put("IsDir", false).put("Size", 1)) }
            JSONObject().put("list", list)
        }
        val entries = RcloneRemote("xfa", rpc, tmp.root).list("/")
        assertEquals(listOf("ok.txt"), entries.map { it.name })
        assertEquals("/ok.txt", entries.single().path)
    }

    @Test fun shizukuListingDropsTrapNames() {
        val fake = object : IFileService {
            override fun asBinder() = null
            override fun destroy() = Unit
            override fun list(path: String): String {
                val a = JSONArray()
                (traps + "com.exemple").forEach { a.put(JSONObject().put("n", it).put("d", true).put("s", 0).put("m", 1)) }
                return a.toString()
            }
            override fun openRead(path: String) = throw IllegalStateException(FileService.DENIED)
            override fun openWrite(path: String) = throw IllegalStateException(FileService.DENIED)
            override fun mkdir(path: String) = Unit
            override fun rename(from: String, to: String) = Unit
            override fun delete(path: String) = Unit
            override fun exists(path: String) = true
        }
        assertEquals(listOf("com.exemple"), ShizukuRemote { fake }.list("/data").map { it.name })
    }
}
