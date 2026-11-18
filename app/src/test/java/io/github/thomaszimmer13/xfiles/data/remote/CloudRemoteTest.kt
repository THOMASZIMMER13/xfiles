package io.github.thomaszimmer13.xfiles.data.remote

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Clients Dropbox, OneDrive et Google Drive contre un serveur simulé (requêtes et réponses des API). */
class CloudRemoteTest {
    private lateinit var server: MockWebServer

    private class Tokens : TokenSource {
        var issued = 0
        val invalidated = mutableListOf<String>()

        override fun token() = "t${++issued}"

        override fun invalidate(token: String) {
            invalidated += token
        }
    }

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun base(path: String) = server.url(path).toString().trimEnd('/')

    // --- Dropbox ---

    @Test fun dropboxListsPagesAndRoot() {
        server.enqueue(json("""{"entries":[{".tag":"folder","name":"Photos","path_display":"/Photos"},{".tag":"file","name":"a.txt","size":12,"server_modified":"2026-03-01T10:00:00Z"},{".tag":"deleted","name":"x"}],"cursor":"c1","has_more":true}"""))
        server.enqueue(json("""{"entries":[{".tag":"file","name":"été.pdf","size":5,"server_modified":"2026-03-02T10:00:00Z"}],"cursor":"c2","has_more":false}"""))
        val dbx = DropboxRemote(Tokens(), api = base("/2"), content = base("/c"))
        val list = dbx.list("/")
        assertEquals(listOf("Photos", "a.txt", "été.pdf"), list.map { it.name })
        assertEquals("/a.txt", list[1].path)
        assertTrue(list[0].isDirectory)
        assertEquals(12L, list[1].size)
        assertEquals(java.time.Instant.parse("2026-03-01T10:00:00Z").toEpochMilli(), list[1].lastModified)
        val first = server.takeRequest()
        assertEquals("/2/files/list_folder", first.url.encodedPath)
        assertEquals("Bearer t1", first.headers["Authorization"])
        assertEquals("", JSONObject(first.body!!.utf8()).getString("path"))
        val second = server.takeRequest()
        assertEquals("/2/files/list_folder/continue", second.url.encodedPath)
        assertEquals("c1", JSONObject(second.body!!.utf8()).getString("cursor"))
    }

    @Test fun dropboxDownloadUsesAsciiHeaderAndRetriesAfter401() {
        server.enqueue(json("""{"error_summary":"expired_access_token/"}""", 401))
        server.enqueue(MockResponse.Builder().code(200).body("contenu").build())
        val tokens = Tokens()
        val dbx = DropboxRemote(tokens, api = base("/2"), content = base("/c"))
        val out = ByteArrayOutputStream()
        dbx.download("/Été/note.txt", out)
        assertEquals("contenu", out.toString("UTF-8"))
        assertEquals(listOf("t1"), tokens.invalidated)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer t2", retry.headers["Authorization"])
        val arg = retry.headers["Dropbox-API-Arg"]!!
        assertTrue(arg, arg.all { it.code < 0x80 })
        assertEquals("/Été/note.txt", JSONObject(arg).getString("path"))
    }

    @Test fun dropboxErrorsAreTranslated() {
        server.enqueue(json("""{"error_summary":"path/conflict/folder/..","error":{}}""", 409))
        server.enqueue(json("""{"error_summary":"path_lookup/not_found/..","error":{}}""", 409))
        val dbx = DropboxRemote(Tokens(), api = base("/2"), content = base("/c"))
        assertReason(RemoteException.Reason.EXISTS) { dbx.mkdir("/Photos") }
        assertReason(RemoteException.Reason.NOT_FOUND) { dbx.delete("/rien", false) }
    }

    @Test fun dropboxUploadSendsContent() {
        server.enqueue(json("""{"name":"b.txt"}"""))
        val dbx = DropboxRemote(Tokens(), api = base("/2"), content = base("/c"))
        val data = "bonjour".toByteArray()
        var reported = 0L
        dbx.upload(ByteArrayInputStream(data), data.size.toLong(), "/b.txt") { done, _ -> reported = done }
        val r = server.takeRequest()
        assertEquals("/c/files/upload", r.url.encodedPath)
        assertEquals("bonjour", r.body!!.utf8())
        assertEquals("overwrite", JSONObject(r.headers["Dropbox-API-Arg"]!!).getString("mode"))
        assertEquals(7L, reported)
    }

    // --- OneDrive ---

    @Test fun oneDriveListsByPathAndFollowsNextLink() {
        val next = server.url("/v1.0/next?page=2").toString()
        server.enqueue(json("""{"value":[{"name":"Vacances","folder":{"childCount":2},"lastModifiedDateTime":"2026-01-01T00:00:00.123Z"}],"@odata.nextLink":"$next"}"""))
        server.enqueue(json("""{"value":[{"name":"plan.docx","size":2048,"file":{},"lastModifiedDateTime":"2026-01-02T00:00:00Z"}]}"""))
        val od = OneDriveRemote(Tokens(), graph = base("/v1.0"))
        val list = od.list("/Photos été")
        assertEquals(listOf("Vacances", "plan.docx"), list.map { it.name })
        assertTrue(list[0].isDirectory)
        assertEquals("/Photos été/plan.docx", list[1].path)
        assertEquals(2048L, list[1].size)
        val first = server.takeRequest()
        assertEquals("/v1.0/me/drive/root:/Photos%20%C3%A9t%C3%A9:/children", first.url.encodedPath)
        assertEquals("/v1.0/next", server.takeRequest().url.encodedPath)
    }

    @Test fun oneDriveRootUploadRenameAndErrors() {
        server.enqueue(json("""{"id":"1"}""", 201))
        server.enqueue(json("""{"id":"1"}"""))
        server.enqueue(json("""{"error":{"code":"itemNotFound","message":"x"}}""", 404))
        server.enqueue(json("""{"error":{"code":"nameAlreadyExists","message":"x"}}""", 409))
        val od = OneDriveRemote(Tokens(), graph = base("/v1.0"))
        od.upload(ByteArrayInputStream("abc".toByteArray()), 3, "/a.txt")
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1.0/me/drive/root:/a.txt:/content", put.url.encodedPath)
        assertEquals("abc", put.body!!.utf8())
        od.rename("/a.txt", "/Docs/b.txt")
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        val body = JSONObject(patch.body!!.utf8())
        assertEquals("b.txt", body.getString("name"))
        assertEquals("/drive/root:/Docs", body.getJSONObject("parentReference").getString("path"))
        assertReason(RemoteException.Reason.NOT_FOUND) { od.delete("/rien", false) }
        assertReason(RemoteException.Reason.EXISTS) { od.mkdir("/Docs") }
        val mkdir = server.run { takeRequest(); takeRequest() }
        assertEquals("/v1.0/me/drive/root/children", mkdir.url.encodedPath)
    }

    // --- Google Drive ---

    @Test fun googleListsWithExportNamesAndDownloadsByCachedId() {
        server.enqueue(json("""{"files":[{"id":"f1","name":"Docs","mimeType":"application/vnd.google-apps.folder"},{"id":"g1","name":"Rapport","mimeType":"application/vnd.google-apps.document"},{"id":"x1","name":"Formulaire","mimeType":"application/vnd.google-apps.form"},{"id":"p1","name":"photo.jpg","mimeType":"image/jpeg","size":"321","modifiedTime":"2026-05-05T05:05:05.000Z"}]}"""))
        server.enqueue(MockResponse.Builder().code(200).body("docx").build())
        val g = GoogleDriveRemote(Tokens(), api = base("/drive/v3"), upload = base("/upload/drive/v3"))
        val list = g.list("/")
        assertEquals(listOf("Docs", "Rapport.docx", "photo.jpg"), list.map { it.name })
        assertEquals(321L, list[2].size)
        val q = server.takeRequest().url.queryParameter("q")!!
        assertTrue(q, q.startsWith("'root' in parents"))
        val out = ByteArrayOutputStream()
        g.download("/Rapport.docx", out)
        val export = server.takeRequest()
        assertEquals("/drive/v3/files/g1/export", export.url.encodedPath)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", export.url.queryParameter("mimeType"))
        assertEquals("docx", out.toString("UTF-8"))
    }

    @Test fun googleResolvesPathsAndUploadsNewFile() {
        // « /Docs » puis « l'été.txt » cherchés dossier par dossier.
        server.enqueue(json("""{"files":[{"id":"d1","mimeType":"application/vnd.google-apps.folder"}]}"""))
        server.enqueue(json("""{"files":[]}"""))
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", server.url("/session/1").toString()).body("").build())
        server.enqueue(json("""{"id":"n1","mimeType":"text/plain"}"""))
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val g = GoogleDriveRemote(Tokens(), api = base("/drive/v3"), upload = base("/upload/drive/v3"))
        g.upload(ByteArrayInputStream("salut".toByteArray()), 5, "/Docs/l'été.txt")
        val folderQuery = server.takeRequest().url.queryParameter("q")!!
        assertTrue(folderQuery, "'root' in parents and name = 'Docs'" in folderQuery)
        val fileQuery = server.takeRequest().url.queryParameter("q")!!
        assertTrue(fileQuery, "'d1' in parents and name = 'l\\'été.txt'" in fileQuery)
        val start = server.takeRequest()
        assertEquals("/upload/drive/v3/files", start.url.encodedPath)
        assertEquals("resumable", start.url.queryParameter("uploadType"))
        val meta = JSONObject(start.body!!.utf8())
        assertEquals("l'été.txt", meta.getString("name"))
        assertEquals("d1", meta.getJSONArray("parents").getString(0))
        val content = server.takeRequest()
        assertEquals("/session/1", content.url.encodedPath)
        assertEquals("salut", content.body!!.utf8())
        // Le nouveau fichier est connu : le télécharger ne refait pas de recherche.
        g.download("/Docs/l'été.txt", ByteArrayOutputStream())
        val get = server.takeRequest()
        assertEquals("/drive/v3/files/n1", get.url.encodedPath)
        assertEquals("media", get.url.queryParameter("alt"))
    }

    @Test fun googleMissingPathIsNotFound() {
        server.enqueue(json("""{"files":[]}"""))
        val g = GoogleDriveRemote(Tokens(), api = base("/drive/v3"), upload = base("/upload/drive/v3"))
        assertReason(RemoteException.Reason.NOT_FOUND) { g.list("/Inconnu") }
        assertFalse(GoogleDriveRemote.escape("a'b\\c").contains("a'b"))
    }

    @Test fun pkceChallengeMatchesRfcExample() {
        // RFC 7636, annexe B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", CloudOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = CloudOAuth("key", "", scheme = "xfiles-auth").authorizeUrl(Protocol.DROPBOX, "v", "s")
        assertTrue(url, "redirect_uri=xfiles-auth%3A%2F%2Fdropbox" in url && "token_access_type=offline" in url)
    }

    private fun assertReason(reason: RemoteException.Reason, block: () -> Unit) {
        try {
            block()
            fail("erreur attendue : $reason")
        } catch (e: RemoteException) {
            assertEquals(e.message, reason, e.reason)
        }
    }
}
