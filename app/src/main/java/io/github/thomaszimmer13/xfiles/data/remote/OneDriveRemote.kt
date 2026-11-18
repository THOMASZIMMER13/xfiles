package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

/**
 * OneDrive (Microsoft Graph) : éléments adressés par leur chemin (« /me/drive/root:/a/b: »),
 * envoi direct jusqu'à 4 Mo, session d'envoi en morceaux au-delà. Supprimer met à la corbeille.
 */
class OneDriveRemote(
    tokens: TokenSource,
    private val graph: String = "https://graph.microsoft.com/v1.0",
) : RemoteClient {
    private val http = CloudHttp(tokens, ::error)

    /** « /me/drive/root » ou « /me/drive/root:/Photos/été%202026: ». */
    private fun item(path: String): String =
        if (path == "/" || path.isEmpty()) "$graph/me/drive/root" else "$graph/me/drive/root:${encode(path)}:"

    override fun list(path: String): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        var next: String? = "${item(path)}/children?\$top=999&\$select=name,size,folder,file,lastModifiedDateTime"
        while (next != null) {
            val url = next
            val page = http.use(path, build = { url(url).get() }) { JSONObject(it.body.string()) }
            val values = page.getJSONArray("value")
            for (i in 0 until values.length()) {
                val v = values.getJSONObject(i)
                val name = v.getString("name")
                val isDir = v.has("folder")
                result += RemoteEntry(name, RemoteClient.child(path, name), isDir, if (isDir) 0 else v.optLong("size"), CloudHttp.parseTime(v.optString("lastModifiedDateTime")))
            }
            next = page.optString("@odata.nextLink").takeIf { it.isNotEmpty() }
        }
        return result
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        // Graph répond par une redirection vers une adresse déjà autorisée (le jeton n'y est pas envoyé).
        http.use(path, build = { url("${item(path)}/content").get() }) { response ->
            copyWithProgress(response.body.byteStream(), out, response.body.contentLength(), progress)
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        if (size in 0..SIMPLE_LIMIT) {
            http.use(path, build = { url("${item(path)}/content").put(StreamBody(input, size, progress = progress)) }) { }
            return
        }
        val body = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace"))
        val uploadUrl = http.use(path, build = { url("${item(path)}/createUploadSession").post(body.toString().toRequestBody(CloudHttp.JSON)) }) {
            JSONObject(it.body.string()).getString("uploadUrl")
        }
        var offset = 0L
        try {
            while (offset < size) {
                val length = minOf(CHUNK, size - offset)
                // Adresse de session déjà autorisée : pas d'en-tête Authorization.
                val request = Request.Builder().url(uploadUrl)
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/$size")
                    .put(StreamBody(input, length, offset = offset, total = size, progress = progress)).build()
                CloudHttp.send(request).use { r -> if (!r.isSuccessful) throw error(r.code, runCatching { r.body.string() }.getOrDefault(""), path) }
                offset += length
            }
        } catch (e: Exception) {
            // Session abandonnée : on la supprime (sans attendre son expiration).
            runCatching { CloudHttp.send(Request.Builder().url(uploadUrl).delete().build()).close() }
            throw e
        }
    }

    override fun mkdir(path: String) {
        val parent = RemoteClient.parent(path)
        val body = JSONObject().put("name", RemoteClient.nameOf(path)).put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail")
        http.use(path, build = { url("${item(parent)}/children").post(body.toString().toRequestBody(CloudHttp.JSON)) }) { }
    }

    override fun rename(from: String, to: String) {
        val body = JSONObject().put("name", RemoteClient.nameOf(to))
        val newParent = RemoteClient.parent(to)
        if (newParent != RemoteClient.parent(from)) {
            body.put("parentReference", JSONObject().put("path", if (newParent == "/") "/drive/root:" else "/drive/root:$newParent"))
        }
        // Sans « replace », un élément du même nom donne une erreur 409 (déjà existant).
        http.use(from, build = { url(item(from)).patch(body.toString().toRequestBody(CloudHttp.JSON)) }) { }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        http.use(path, build = { url(item(path)).delete() }) { }
    }

    override val isAlive: Boolean get() = true

    override fun close() = Unit

    private fun error(code: Int, body: String, path: String): RemoteException {
        val graphCode = runCatching { JSONObject(body).getJSONObject("error").optString("code") }.getOrDefault("")
        val reason = when (graphCode) {
            "itemNotFound" -> RemoteException.Reason.NOT_FOUND
            "nameAlreadyExists" -> RemoteException.Reason.EXISTS
            "accessDenied", "quotaLimitReached", "notAllowed" -> RemoteException.Reason.DENIED
            "InvalidAuthenticationToken", "unauthenticated" -> RemoteException.Reason.AUTH
            else -> CloudHttp.reasonOf(code)
        }
        return RemoteException(reason, "$path : HTTP $code $graphCode")
    }

    companion object {
        private const val SIMPLE_LIMIT = 4L * 1024 * 1024

        /** 10 Mio : multiple de 320 Kio, comme l'exige Graph. */
        private const val CHUNK = 10L * 1024 * 1024

        /** Chaque segment du chemin encodé pour l'adresse (« été 2026 » → « %C3%A9t%C3%A9%202026 »). */
        fun encode(path: String): String = path.split('/').filter { it.isNotEmpty() }
            .joinToString("/", prefix = "/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
