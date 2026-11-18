package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Dropbox (API v2) : chemins natifs (« » pour la racine), envoi en une fois jusqu'à 150 Mo, en
 * plusieurs morceaux au-delà. Les éléments supprimés restent récupérables 30 jours sur Dropbox.
 */
class DropboxRemote(
    tokens: TokenSource,
    private val api: String = "https://api.dropboxapi.com/2",
    private val content: String = "https://content.dropboxapi.com/2",
) : RemoteClient {
    private val http = CloudHttp(tokens, ::error)

    private fun dbx(path: String) = if (path == "/" || path.isEmpty()) "" else path.trimEnd('/')

    private fun rpc(endpoint: String, path: String, args: JSONObject): JSONObject =
        http.use(path, build = { url("$api/$endpoint").post(args.toString().toRequestBody(CloudHttp.JSON)) }) { JSONObject(it.body.string()) }

    override fun list(path: String): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        var page = rpc("files/list_folder", path, JSONObject().put("path", dbx(path)).put("limit", 2000))
        while (true) {
            val entries = page.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val tag = e.optString(".tag")
                if (tag != "file" && tag != "folder") continue
                val name = e.getString("name")
                val isDir = tag == "folder"
                result += RemoteEntry(name, RemoteClient.child(path, name), isDir, if (isDir) 0 else e.optLong("size"), CloudHttp.parseTime(e.optString("server_modified")))
            }
            if (!page.optBoolean("has_more")) break
            page = rpc("files/list_folder/continue", path, JSONObject().put("cursor", page.getString("cursor")))
        }
        return result
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        http.use(path, build = {
            url("$content/files/download").header(ARG, headerJson(JSONObject().put("path", dbx(path)))).post(ByteArray(0).toRequestBody(null))
        }) { response ->
            val total = response.body.contentLength()
            copyWithProgress(response.body.byteStream(), out, total, progress)
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        val commit = JSONObject().put("path", dbx(path)).put("mode", "overwrite").put("autorename", false).put("mute", true)
        if (size in 0..SIMPLE_LIMIT) {
            http.use(path, build = {
                url("$content/files/upload").header(ARG, headerJson(commit)).post(StreamBody(input, size, progress = progress))
            }) { }
            return
        }
        // Gros fichier : session d'envoi en morceaux de 32 Mo (multiple de 4 Mo exigé).
        val first = minOf(CHUNK, size)
        val sessionId = http.use(path, build = {
            url("$content/files/upload_session/start").header(ARG, headerJson(JSONObject().put("close", false)))
                .post(StreamBody(input, first, total = size, progress = progress))
        }) { JSONObject(it.body.string()).getString("session_id") }
        var offset = first
        while (size - offset > CHUNK) {
            val cursor = JSONObject().put("session_id", sessionId).put("offset", offset)
            val start = offset
            http.use(path, retryOnAuth = false, build = {
                url("$content/files/upload_session/append_v2").header(ARG, headerJson(JSONObject().put("cursor", cursor).put("close", false)))
                    .post(StreamBody(input, CHUNK, offset = start, total = size, progress = progress))
            }) { }
            offset += CHUNK
        }
        val cursor = JSONObject().put("session_id", sessionId).put("offset", offset)
        val start = offset
        http.use(path, retryOnAuth = false, build = {
            url("$content/files/upload_session/finish").header(ARG, headerJson(JSONObject().put("cursor", cursor).put("commit", commit)))
                .post(StreamBody(input, size - start, offset = start, total = size, progress = progress))
        }) { }
    }

    override fun mkdir(path: String) {
        rpc("files/create_folder_v2", path, JSONObject().put("path", dbx(path)).put("autorename", false))
    }

    override fun rename(from: String, to: String) {
        rpc("files/move_v2", from, JSONObject().put("from_path", dbx(from)).put("to_path", dbx(to)).put("autorename", false))
    }

    override fun delete(path: String, isDirectory: Boolean) {
        rpc("files/delete_v2", path, JSONObject().put("path", dbx(path)))
    }

    override val isAlive: Boolean get() = true

    override fun close() = Unit

    private fun error(code: Int, body: String, path: String): RemoteException {
        // 409 : erreur décrite par « error_summary » (« path/not_found/.. », « path/conflict/folder/.. »).
        val summary = runCatching { JSONObject(body).optString("error_summary") }.getOrDefault("")
        val reason = when {
            code == 409 && "not_found" in summary -> RemoteException.Reason.NOT_FOUND
            code == 409 && "conflict" in summary -> RemoteException.Reason.EXISTS
            code == 409 && ("no_write_permission" in summary || "insufficient_space" in summary || "disallowed_name" in summary) -> RemoteException.Reason.DENIED
            code == 409 -> RemoteException.Reason.PROTOCOL
            code == 400 && "invalid_access_token" in body -> RemoteException.Reason.AUTH
            else -> CloudHttp.reasonOf(code)
        }
        return RemoteException(reason, "$path : HTTP $code $summary")
    }

    companion object {
        private const val ARG = "Dropbox-API-Arg"
        private const val SIMPLE_LIMIT = 150L * 1024 * 1024
        private const val CHUNK = 32L * 1024 * 1024

        /** En-tête « Dropbox-API-Arg » : JSON en ASCII, les autres caractères en « \uXXXX ». */
        fun headerJson(o: JSONObject): String = buildString {
            o.toString().forEach { c -> if (c.code in 0x20..0x7e) append(c) else append("\\u%04x".format(c.code)) }
        }
    }
}
