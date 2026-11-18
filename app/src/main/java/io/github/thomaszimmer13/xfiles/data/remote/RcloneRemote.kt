package io.github.thomaszimmer13.xfiles.data.remote

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Compte en ligne par rclone. [remote] : nom de la configuration rclone (« xf1a2b… »).
 * Téléchargement et envoi passent par un fichier de passage dans [workDir], avec l'avancement lu
 * dans les statistiques de rclone ; un fil interrompu arrête la copie en cours.
 */
class RcloneRemote(
    private val remote: String,
    private val rpc: RcloneRpc,
    private val workDir: File,
    private val pollMs: Long = 300,
) : RemoteClient {
    private val fs = "$remote:"

    private fun rel(path: String) = path.trim('/')

    private fun call(method: String, path: String, input: JSONObject): JSONObject = try {
        rpc.call(method, input)
    } catch (e: RcloneException) {
        throw translate(e, path)
    }

    override fun list(path: String): List<RemoteEntry> {
        val out = call("operations/list", path, JSONObject().put("fs", fs).put("remote", rel(path)).put("opt", JSONObject()))
        val list = out.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.getJSONObject(i)
            val name = RemoteClient.safeName(o.getString("Name")) ?: return@mapNotNull null
            val isDir = o.optBoolean("IsDir")
            RemoteEntry(name, RemoteClient.child(path, name), isDir, if (isDir) 0 else o.optLong("Size", -1).takeIf { it >= 0 } ?: -1L, parseTime(o.optString("ModTime")))
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        val temp = File.createTempFile("xfdown", null, workDir)
        try {
            copyJob(
                path,
                JSONObject().put("srcFs", fs).put("srcRemote", rel(path)).put("dstFs", temp.parent).put("dstRemote", temp.name),
                progress,
            )
            temp.inputStream().use { copyWithProgress(it, out, temp.length(), null) }
        } finally {
            temp.delete()
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        val temp = File.createTempFile("xfup", null, workDir)
        try {
            temp.outputStream().use { copyWithProgress(input, it, size, null) }
            copyJob(
                path,
                JSONObject().put("srcFs", temp.parent).put("srcRemote", temp.name).put("dstFs", fs).put("dstRemote", rel(path)),
                progress,
            )
        } finally {
            temp.delete()
        }
    }

    /** Copie d'un fichier en tâche de fond rclone, suivie jusqu'à la fin ; annulée si le fil est interrompu. */
    private fun copyJob(path: String, args: JSONObject, progress: TransferProgress?) {
        val group = "xf-copy-" + counter.incrementAndGet()
        val job = call("operations/copyfile", path, args.put("_async", true).put("_group", group)).getLong("jobid")
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                val status = call("job/status", path, JSONObject().put("jobid", job))
                if (progress != null) {
                    runCatching { rpc.call("core/stats", JSONObject().put("group", group)) }.getOrNull()?.let { stats ->
                        progress.onProgress(stats.optLong("bytes"), stats.optLong("totalBytes", -1).takeIf { it > 0 } ?: -1)
                    }
                }
                if (status.optBoolean("finished")) {
                    if (!status.optBoolean("success")) throw translate(RcloneException(500, status.optString("error")), path)
                    return
                }
                try {
                    Thread.sleep(pollMs)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException().apply { initCause(e) }
                }
            }
        } catch (e: InterruptedIOException) {
            runCatching { rpc.call("job/stop", JSONObject().put("jobid", job)) }
            throw e
        } finally {
            runCatching { rpc.call("core/stats-delete", JSONObject().put("group", group)) }
        }
    }

    override fun mkdir(path: String) {
        if (exists(path)) throw RemoteException(RemoteException.Reason.EXISTS, path)
        call("operations/mkdir", path, JSONObject().put("fs", fs).put("remote", rel(path)))
    }

    override fun rename(from: String, to: String) {
        if (exists(to)) throw RemoteException(RemoteException.Reason.EXISTS, to)
        val isDir = stat(from)?.optBoolean("IsDir") ?: throw RemoteException(RemoteException.Reason.NOT_FOUND, from)
        if (isDir) {
            call("sync/move", from, JSONObject().put("srcFs", fs + rel(from)).put("dstFs", fs + rel(to)).put("deleteEmptySrcDirs", true))
            // Le dossier vide de départ peut rester (selon le service).
            runCatching { rpc.call("operations/rmdir", JSONObject().put("fs", fs).put("remote", rel(from))) }
        } else {
            call("operations/movefile", from, JSONObject().put("srcFs", fs).put("srcRemote", rel(from)).put("dstFs", fs).put("dstRemote", rel(to)))
        }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        if (isDirectory) {
            call("operations/purge", path, JSONObject().put("fs", fs).put("remote", rel(path)))
        } else {
            call("operations/deletefile", path, JSONObject().put("fs", fs).put("remote", rel(path)))
        }
    }

    private fun stat(path: String): JSONObject? =
        call("operations/stat", path, JSONObject().put("fs", fs).put("remote", rel(path)).put("opt", JSONObject())).optJSONObject("item")

    private fun exists(path: String): Boolean = try {
        stat(path) != null
    } catch (e: RemoteException) {
        if (e.reason == RemoteException.Reason.NOT_FOUND) false else throw e
    }

    override val isAlive: Boolean get() = true

    override fun close() = Unit

    companion object {
        private val counter = AtomicLong()

        private fun parseTime(s: String?): Long = s?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        /** Message d'erreur de rclone traduit en raison annonçable. */
        fun translate(e: RcloneException, path: String): RemoteException {
            val m = e.message.orEmpty().lowercase()
            val reason = when {
                "not found" in m || "doesn't exist" in m || "does not exist" in m || "no such file" in m -> RemoteException.Reason.NOT_FOUND
                "already exists" in m || "conflict" in m -> RemoteException.Reason.EXISTS
                "token" in m || "invalid_grant" in m || "unauthorized" in m || "401" in m || "didn't find section" in m -> RemoteException.Reason.AUTH
                "permission" in m || "forbidden" in m || "403" in m || "insufficient" in m || "quota" in m -> RemoteException.Reason.DENIED
                "no such host" in m || "dial tcp" in m || "network is unreachable" in m || "timeout" in m || "connection refused" in m || "connection reset" in m -> RemoteException.Reason.UNREACHABLE
                "x509" in m || "certificate" in m || "tls" in m -> RemoteException.Reason.TLS
                else -> RemoteException.Reason.PROTOCOL
            }
            return RemoteException(reason, "$path : ${e.message}", e)
        }
    }
}
