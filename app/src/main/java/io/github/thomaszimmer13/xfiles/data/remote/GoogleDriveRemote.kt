package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Drive (API v3). Drive repère les éléments par identifiant : les chemins sont résolus
 * dossier par dossier, et gardés en mémoire. Les documents Google (Docs, Sheets, Slides, Drawings)
 * apparaissent avec l'extension de leur format d'export (« Budget.xlsx ») et sont exportés à
 * l'ouverture. Supprimer met à la corbeille.
 */
class GoogleDriveRemote(
    tokens: TokenSource,
    private val api: String = "https://www.googleapis.com/drive/v3",
    private val upload: String = "https://www.googleapis.com/upload/drive/v3",
) : RemoteClient {
    private val http = CloudHttp(tokens, ::error)

    private data class Node(val id: String, val mime: String) {
        val isFolder get() = mime == FOLDER
    }

    private val known = ConcurrentHashMap<String, Node>().apply { put("/", Node("root", FOLDER)) }

    private fun key(path: String) = if (path.isEmpty() || path == "/") "/" else "/" + path.trim('/')

    private fun endpoint(base: String, segments: String, vararg query: Pair<String, String>): HttpUrl =
        "$base/$segments".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    /** Élément au chemin donné, ou null s'il n'existe pas. */
    private fun find(path: String): Node? {
        val k = key(path)
        known[k]?.let { return it }
        val parent = find(RemoteClient.parent(k)) ?: return null
        if (!parent.isFolder) return null
        val name = RemoteClient.nameOf(k)
        // Nom exact d'abord ; sinon un document Google affiché avec son extension d'export.
        val exact = query(parent.id, name)
        val node = exact ?: EXPORTS.entries.firstNotNullOfOrNull { (mime, export) ->
            if (name.endsWith(".${export.extension}")) query(parent.id, name.removeSuffix(".${export.extension}"), mime) else null
        }
        node?.let { known[k] = it }
        return node
    }

    private fun resolve(path: String): Node = find(path) ?: throw RemoteException(RemoteException.Reason.NOT_FOUND, path)

    private fun query(parentId: String, name: String, mime: String? = null): Node? {
        val q = buildString {
            append("'").append(escape(parentId)).append("' in parents and name = '").append(escape(name)).append("' and trashed = false")
            if (mime != null) append(" and mimeType = '").append(mime).append("'")
        }
        val files = http.use(name, build = { url(endpoint(api, "files", "q" to q, "fields" to "files(id,mimeType)", "pageSize" to "10", "spaces" to "drive")).get() }) {
            JSONObject(it.body.string()).getJSONArray("files")
        }
        if (files.length() == 0) return null
        val f = files.getJSONObject(0)
        return Node(f.getString("id"), f.getString("mimeType"))
    }

    override fun list(path: String): List<RemoteEntry> {
        val folder = resolve(path)
        if (!folder.isFolder) throw RemoteException(RemoteException.Reason.NOT_FOUND, path)
        val result = mutableListOf<RemoteEntry>()
        var pageToken: String? = null
        do {
            val params = mutableListOf(
                "q" to "'${escape(folder.id)}' in parents and trashed = false",
                "fields" to "nextPageToken,files(id,name,mimeType,size,modifiedTime)",
                "pageSize" to "1000",
                "spaces" to "drive",
            )
            pageToken?.let { params += "pageToken" to it }
            val page = http.use(path, build = { url(endpoint(api, "files", *params.toTypedArray())).get() }) { JSONObject(it.body.string()) }
            val files = page.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val mime = f.getString("mimeType")
                val isDir = mime == FOLDER
                val name = when {
                    isDir -> f.getString("name")
                    mime.startsWith(GOOGLE_APPS) -> EXPORTS[mime]?.let { f.getString("name") + "." + it.extension } ?: continue
                    else -> f.getString("name")
                }
                val childPath = RemoteClient.child(path, name)
                known.putIfAbsent(key(childPath), Node(f.getString("id"), mime))
                result += RemoteEntry(name, childPath, isDir, if (isDir) 0 else f.optString("size").toLongOrNull() ?: 0L, CloudHttp.parseTime(f.optString("modifiedTime")))
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return result
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        val node = resolve(path)
        val export = EXPORTS[node.mime]
        val target = if (export != null) endpoint(api, "files/${node.id}/export", "mimeType" to export.mime) else endpoint(api, "files/${node.id}", "alt" to "media")
        http.use(path, build = { url(target).get() }) { response ->
            copyWithProgress(response.body.byteStream(), out, response.body.contentLength(), progress)
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        val existing = find(path)?.takeIf { !it.isFolder && !it.mime.startsWith(GOOGLE_APPS) }
        // Envoi « reprenable » : une session, puis le contenu en une requête (sans limite de taille).
        val session = if (existing != null) {
            http.use(path, build = {
                url(endpoint(upload, "files/${existing.id}", "uploadType" to "resumable")).patch("{}".toRequestBody(CloudHttp.JSON)).header("X-Upload-Content-Length", size.toString())
            }) { it.header("Location") }
        } else {
            val parent = resolve(RemoteClient.parent(path))
            val meta = JSONObject().put("name", RemoteClient.nameOf(path)).put("parents", JSONArray().put(parent.id))
            http.use(path, build = {
                url(endpoint(upload, "files", "uploadType" to "resumable", "fields" to "id,mimeType")).post(meta.toString().toRequestBody(CloudHttp.JSON)).header("X-Upload-Content-Length", size.toString())
            }) { it.header("Location") }
        } ?: throw RemoteException(RemoteException.Reason.PROTOCOL, "$path : pas de session d'envoi")
        val created = CloudHttp.send(Request.Builder().url(session).put(StreamBody(input, size, progress = progress)).build()).use { r ->
            if (!r.isSuccessful) throw error(r.code, runCatching { r.body.string() }.getOrDefault(""), path)
            runCatching { JSONObject(r.body.string()) }.getOrNull()
        }
        created?.optString("id")?.takeIf { it.isNotEmpty() }?.let { known[key(path)] = Node(it, created.optString("mimeType", "application/octet-stream")) }
    }

    override fun mkdir(path: String) {
        if (find(path) != null) throw RemoteException(RemoteException.Reason.EXISTS, path)
        val parent = resolve(RemoteClient.parent(path))
        val meta = JSONObject().put("name", RemoteClient.nameOf(path)).put("mimeType", FOLDER).put("parents", JSONArray().put(parent.id))
        val id = http.use(path, build = { url(endpoint(api, "files", "fields" to "id")).post(meta.toString().toRequestBody(CloudHttp.JSON)) }) {
            JSONObject(it.body.string()).getString("id")
        }
        known[key(path)] = Node(id, FOLDER)
    }

    override fun rename(from: String, to: String) {
        val node = resolve(from)
        if (find(to) != null) throw RemoteException(RemoteException.Reason.EXISTS, to)
        // Document Google : le nom sur Drive est sans l'extension d'export.
        val newName = RemoteClient.nameOf(to).let { n -> EXPORTS[node.mime]?.let { n.removeSuffix(".${it.extension}") } ?: n }
        val oldParent = resolve(RemoteClient.parent(from))
        val newParent = resolve(RemoteClient.parent(to))
        val params = mutableListOf("fields" to "id")
        if (oldParent.id != newParent.id) {
            params += "addParents" to newParent.id
            params += "removeParents" to oldParent.id
        }
        http.use(from, build = { url(endpoint(api, "files/${node.id}", *params.toTypedArray())).patch(JSONObject().put("name", newName).toString().toRequestBody(CloudHttp.JSON)) }) { }
        forget(from)
        known[key(to)] = node
    }

    override fun delete(path: String, isDirectory: Boolean) {
        val node = resolve(path)
        http.use(path, build = { url(endpoint(api, "files/${node.id}", "fields" to "id")).patch(JSONObject().put("trashed", true).toString().toRequestBody(CloudHttp.JSON)) }) { }
        forget(path)
    }

    /** Oublie un chemin et tout ce qu'il contenait. */
    private fun forget(path: String) {
        val k = key(path)
        known.keys.removeIf { it == k || it.startsWith("$k/") }
    }

    override val isAlive: Boolean get() = true

    override fun close() = Unit

    private fun error(code: Int, body: String, path: String): RemoteException {
        val reasonText = runCatching { JSONObject(body).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason") }.getOrDefault("")
        val reason = when (reasonText) {
            "notFound" -> RemoteException.Reason.NOT_FOUND
            "insufficientFilePermissions", "storageQuotaExceeded", "cannotModifyViewersCanCopyContent" -> RemoteException.Reason.DENIED
            "rateLimitExceeded", "userRateLimitExceeded", "backendError" -> RemoteException.Reason.UNREACHABLE
            "authError" -> RemoteException.Reason.AUTH
            else -> if (code == 403 && "accessNotConfigured" in body) RemoteException.Reason.DENIED else CloudHttp.reasonOf(code)
        }
        return RemoteException(reason, "$path : HTTP $code $reasonText")
    }

    private class Export(val mime: String, val extension: String)

    companion object {
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val GOOGLE_APPS = "application/vnd.google-apps."

        private val EXPORTS = mapOf(
            "application/vnd.google-apps.document" to Export("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            "application/vnd.google-apps.spreadsheet" to Export("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            "application/vnd.google-apps.presentation" to Export("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            "application/vnd.google-apps.drawing" to Export("application/pdf", "pdf"),
        )

        /** Texte entre apostrophes d'une requête Drive : « \ » et « ' » échappés. */
        fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
    }
}
