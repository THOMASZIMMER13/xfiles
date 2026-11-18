package io.github.thomaszimmer13.xfiles.data.shizuku

import android.os.ParcelFileDescriptor
import io.github.thomaszimmer13.xfiles.data.remote.RemoteClient
import io.github.thomaszimmer13.xfiles.data.remote.RemoteEntry
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import io.github.thomaszimmer13.xfiles.data.remote.TransferProgress
import io.github.thomaszimmer13.xfiles.data.remote.copyWithProgress
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream

/**
 * Dossier Android (data, obb, media) vu avec les droits de Shizuku, présenté comme un serveur :
 * chemins « /data/com.exemple/… » relatifs à [ShizukuAccess.ROOT].
 */
class ShizukuRemote(private val files: () -> IFileService) : RemoteClient {
    private fun real(path: String) = ShizukuAccess.ROOT + "/" + path.trim('/')

    private fun <T> call(path: String, block: (IFileService) -> T): T = try {
        block(files())
    } catch (e: IllegalStateException) {
        val reason = when (e.message) {
            FileService.NOT_FOUND -> RemoteException.Reason.NOT_FOUND
            FileService.EXISTS -> RemoteException.Reason.EXISTS
            else -> RemoteException.Reason.DENIED
        }
        throw RemoteException(reason, path, e)
    } catch (e: android.os.RemoteException) {
        throw RemoteException(RemoteException.Reason.UNREACHABLE, path, e)
    }

    override fun list(path: String): List<RemoteEntry> {
        val array = JSONArray(call(path) { it.list(real(path)) })
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val name = o.getString("n")
            RemoteEntry(name, RemoteClient.child(path, name), o.getBoolean("d"), o.optLong("s"), o.optLong("m"))
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        val pfd = call(path) { it.openRead(real(path)) }
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> copyWithProgress(input, out, pfd.statSize, progress) }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        val pfd = call(path) { it.openWrite(real(path)) }
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out -> copyWithProgress(input, out, size, progress) }
    }

    override fun mkdir(path: String) = call(path) { it.mkdir(real(path)) }

    override fun rename(from: String, to: String) = call(from) { it.rename(real(from), real(to)) }

    override fun delete(path: String, isDirectory: Boolean) = call(path) { it.delete(real(path)) }

    override val isAlive: Boolean get() = runCatching { files().asBinder().pingBinder() }.getOrDefault(false)

    override fun close() = Unit
}
