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
class FileService(base: String = ShizukuAccess.ROOT) : IFileService.Stub() {
    override fun destroy() = exitProcess(0)

    /** Dossier Android (canonique) et les seuls sous-dossiers où l'on agit (M-01). */
    private val base: String = File(base).canonicalPath
    private val roots: List<String> = ALLOWED.map { "${this.base}/$it" }

    /**
     * Chemin normalisé (liens et « .. » résolus) qui doit être le dossier Android (lecture seule) ou
     * se trouver dans data, obb ou media ; sinon DENIED. Le service a les droits « shell » : sans ce
     * contrôle, un chemin piégé atteindrait tout ce que le débogage peut lire, écrire ou supprimer.
     */
    private fun checked(path: String, write: Boolean): File {
        val f = File(path).canonicalFile
        val p = f.path
        val underRoot = roots.any { p.startsWith("$it/") }
        val ok = when {
            write -> underRoot // jamais la racine Android ni data/obb/media eux-mêmes
            else -> p == base || p in roots || underRoot
        }
        if (!ok) fail(DENIED)
        return f
    }

    private fun existing(path: String, write: Boolean = false): File = checked(path, write).also { if (!it.exists()) fail(NOT_FOUND) }

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
        val file = checked(path, write = true)
        if (file.parentFile?.isDirectory != true) fail(NOT_FOUND)
        return guard {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    override fun mkdir(path: String) {
        val dir = checked(path, write = true)
        if (dir.exists()) fail(EXISTS)
        if (!dir.mkdir()) fail(DENIED)
    }

    override fun rename(from: String, to: String) {
        val source = existing(from, write = true)
        val target = checked(to, write = true)
        if (target.exists()) fail(EXISTS)
        if (!source.renameTo(target)) fail(DENIED)
    }

    override fun delete(path: String) {
        val file = existing(path, write = true)
        if (!file.deleteRecursively()) fail(DENIED)
    }

    override fun exists(path: String): Boolean = checked(path, write = false).exists()

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
        private val ALLOWED = listOf("data", "obb", "media")

        private fun fail(reason: String): Nothing = throw IllegalStateException(reason)
    }
}
