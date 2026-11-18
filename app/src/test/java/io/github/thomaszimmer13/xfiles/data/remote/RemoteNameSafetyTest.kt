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
