package io.github.thomaszimmer13.xfiles.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Client rclone d'XFiles contre un rclone simulé (les vrais appels sont vérifiés par le test Go
 * du dossier « rclone »).
 */
class RcloneRemoteTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRpc(val answer: (String, JSONObject) -> JSONObject) : RcloneRpc {
        val calls = mutableListOf<Pair<String, JSONObject>>()

        override fun call(method: String, input: JSONObject): JSONObject {
            calls += method to input
            return answer(method, input)
        }
    }

    @Test fun listMapsEntriesAndPaths() {
        val rpc = FakeRpc { method, input ->
            assertEquals("operations/list", method)
            assertEquals("xfa:", input.getString("fs"))
            assertEquals("Photos/été", input.getString("remote"))
            JSONObject().put(
                "list",
                JSONArray()
                    .put(JSONObject().put("Name", "Vacances").put("IsDir", true).put("Size", -1).put("ModTime", "2026-01-02T03:04:05.123Z"))
                    .put(JSONObject().put("Name", "Budget.xlsx").put("IsDir", false).put("Size", -1))
                    .put(JSONObject().put("Name", "a.jpg").put("IsDir", false).put("Size", 1234)),
            )
        }
        val list = RcloneRemote("xfa", rpc, tmp.root).list("/Photos/été")
        assertEquals(listOf("Vacances", "Budget.xlsx", "a.jpg"), list.map { it.name })
        assertEquals("/Photos/été/a.jpg", list[2].path)
        assertTrue(list[0].isDirectory)
        assertEquals(0L, list[1].size) // taille inconnue (document Google)
        assertEquals(1234L, list[2].size)
        assertEquals(java.time.Instant.parse("2026-01-02T03:04:05.123Z").toEpochMilli(), list[0].lastModified)
    }

    @Test fun downloadRunsJobReportsProgressAndCleansUp() {
        var polls = 0
        val rpc = FakeRpc { method, input ->
            when (method) {
                "operations/copyfile" -> {
                    assertTrue(input.getBoolean("_async"))
                    assertEquals("doc.txt", input.getString("srcRemote"))
                    File(input.getString("dstFs"), input.getString("dstRemote")).writeText("contenu")
                    JSONObject().put("jobid", 7)
                }
                "job/status" -> JSONObject().put("finished", ++polls >= 2).put("success", true)
                "core/stats" -> JSONObject().put("bytes", 7).put("totalBytes", 7)
                else -> JSONObject()
            }
        }
        val out = ByteArrayOutputStream()
        var reported = 0L
        RcloneRemote("xfa", rpc, tmp.root, pollMs = 1).download("/doc.txt", out) { done, _ -> reported = done }
        assertEquals("contenu", out.toString("UTF-8"))
        assertEquals(7L, reported)
        assertTrue(rpc.calls.any { it.first == "core/stats-delete" })
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun cancelledTransferStopsTheJob() {
        val rpc = FakeRpc { method, _ ->
            when (method) {
                "operations/copyfile" -> JSONObject().put("jobid", 9)
                "job/status" -> {
                    Thread.currentThread().interrupt()
                    JSONObject().put("finished", false)
                }
                else -> JSONObject()
            }
        }
        try {
            RcloneRemote("xfa", rpc, tmp.root, pollMs = 1).upload(ByteArrayInputStream("abc".toByteArray()), 3, "/up.txt", null)
            fail("annulation attendue")
        } catch (_: java.io.InterruptedIOException) {
        } finally {
            Thread.interrupted()
        }
        assertTrue(rpc.calls.any { it.first == "job/stop" && it.second.getInt("jobid") == 9 })
    }

    @Test fun renameFolderUsesMoveAndFileUsesMovefile() {
        val rpc = FakeRpc { method, input ->
            when (method) {
                "operations/stat" -> when (input.getString("remote")) {
                    "Dossier" -> JSONObject().put("item", JSONObject().put("IsDir", true))
                    "f.txt" -> JSONObject().put("item", JSONObject().put("IsDir", false))
                    else -> JSONObject() // absent
                }
                else -> JSONObject()
            }
        }
        val remote = RcloneRemote("xfa", rpc, tmp.root)
        remote.rename("/Dossier", "/Nouveau")
        val move = rpc.calls.first { it.first == "sync/move" }.second
        assertEquals("xfa:Dossier", move.getString("srcFs"))
        assertEquals("xfa:Nouveau", move.getString("dstFs"))
        remote.rename("/f.txt", "/g.txt")
        val movefile = rpc.calls.first { it.first == "operations/movefile" }.second
        assertEquals("g.txt", movefile.getString("dstRemote"))
        // Nom déjà pris : refusé avant tout déplacement.
        try {
            remote.rename("/f.txt", "/Dossier")
            fail()
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.EXISTS, e.reason)
        }
    }

    @Test fun errorsAreTranslated() {
        fun reason(message: String) = RcloneRemote.translate(RcloneException(500, message), "/x").reason
        assertEquals(RemoteException.Reason.NOT_FOUND, reason("error in ListJSON: directory not found"))
        assertEquals(RemoteException.Reason.AUTH, reason("couldn't fetch token: invalid_grant"))
        assertEquals(RemoteException.Reason.UNREACHABLE, reason("Get \"https://www.googleapis.com\": dial tcp: lookup www.googleapis.com: no such host"))
        assertEquals(RemoteException.Reason.DENIED, reason("googleapi: Error 403: The user's Drive storage quota has been exceeded"))
        assertEquals(RemoteException.Reason.PROTOCOL, reason("something odd"))
    }

    @Test fun setupAnswersQuestionsWithDefaultsAndBrowserYes() {
        val answers = mutableListOf<String>()
        val rpc = FakeRpc { method, input ->
            when (method) {
                "config/create" -> JSONObject().put("State", "*oauth-islocal").put("Option", JSONObject().put("Name", "config_is_local").put("ValueStr", "false"))
                "config/update" -> {
                    val opt = input.getJSONObject("opt")
                    assertTrue(opt.getBoolean("continue"))
                    answers += opt.getString("result")
                    if (answers.size == 1) JSONObject().put("State", "choose_type").put("Option", JSONObject().put("Name", "config_type").put("ValueStr", "onedrive"))
                    else JSONObject().put("State", "")
                }
                else -> JSONObject()
            }
        }
        CloudSetup.configure(rpc, "xfa", "onedrive", JSONObject().put("config_template", "<p/>"))
        assertEquals(listOf("true", "onedrive"), answers)
        assertTrue(rpc.calls.all { it.second.getJSONObject("opt").getBoolean("nonInteractive") || it.first == "config/create" })
    }

    @Test fun setupRefusesRetiredSharedGoogleClient() {
        val rpc = FakeRpc { _, _ -> JSONObject().put("State", "client_id_warning").put("Option", JSONObject().put("Name", "config_shared_client_id").put("ValueStr", "false")) }
        try {
            CloudSetup.configure(rpc, "xfa", "drive", JSONObject())
            fail()
        } catch (e: RcloneException) {
            assertEquals("client_id required", e.message)
        }
    }
}
