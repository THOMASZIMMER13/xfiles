package io.github.thomaszimmer13.xfiles.data.shizuku

import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Service lancé par Shizuku dans un processus à part, avec les droits du débogage (utilisateur
 * « shell ») : il voit Android/data et Android/obb. Les erreurs remontent en IllegalStateException
 * dont le message est une raison (NOT_FOUND, EXISTS, DENIED, FAILED), seul type transmis par le binder.
 */
class FileService : IFileService.Stub() {
    override fun destroy() = exitProcess(0)

    private fun existing(path: String): File = File(path).also { if (!it.exists()) fail(NOT_FOUND) }

    override fun list(path: String): String {
        val dir = existing(path)
        if (!dir.isDirectory) fail(NOT_FOUND)
        val children = dir.listFiles() ?: fail(DENIED)
        val out = JSONArray()
        for (f in children) {
            out.put(JSONObject().put("n", f.name).put("d", f.isDirectory).put("s", if (f.isDirectory) 0 else f.length()).put("m", f.lastModified()))
        }
        return out.toString()
    }

    override fun openRead(path: String): ParcelFileDescriptor {
        val file = existing(path)
        if (file.isDirectory) fail(NOT_FOUND)
        return guard { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
    }

    override fun openWrite(path: String): ParcelFileDescriptor {
        val file = File(path)
        if (file.parentFile?.isDirectory != true) fail(NOT_FOUND)
        return guard {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    override fun mkdir(path: String) {
        val dir = File(path)
        if (dir.exists()) fail(EXISTS)
        if (!dir.mkdir()) fail(DENIED)
    }

    override fun rename(from: String, to: String) {
        val source = existing(from)
        val target = File(to)
        if (target.exists()) fail(EXISTS)
        if (!source.renameTo(target)) fail(DENIED)
    }

    override fun delete(path: String) {
        val file = existing(path)
        if (!file.deleteRecursively()) fail(DENIED)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        fail(DENIED)
    } catch (e: java.io.IOException) {
        fail(DENIED)
    }

    companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val EXISTS = "EXISTS"
        const val DENIED = "DENIED"

        private fun fail(reason: String): Nothing = throw IllegalStateException(reason)
    }
}
