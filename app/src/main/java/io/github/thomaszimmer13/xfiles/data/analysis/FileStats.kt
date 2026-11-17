package io.github.thomaszimmer13.xfiles.data.analysis

import java.io.File
import java.security.MessageDigest

/** Taille totale d'un dossier et ce qu'il contient, sous-dossiers compris. */
data class FolderSize(val bytes: Long, val files: Int, val folders: Int)

object FileStats {

    /** Parcourt [dir] (sans suivre les liens symboliques). Opération bloquante. */
    fun folderSize(dir: File, isCancelled: () -> Boolean = { false }): FolderSize {
        var bytes = 0L
        var files = 0
        var folders = 0
        val pending = ArrayDeque<File>().apply { add(dir) }
        while (pending.isNotEmpty()) {
            if (isCancelled()) break
            val current = pending.removeFirst()
            for (child in current.listFiles().orEmpty()) {
                when {
                    isSymlink(child) -> Unit
                    child.isDirectory -> {
                        folders++
                        pending.add(child)
                    }
                    else -> {
                        files++
                        bytes += child.length()
                    }
                }
            }
        }
        return FolderSize(bytes, files, folders)
    }

    /** Empreintes MD5 et SHA-256 de [file], en hexadécimal minuscule, calculées en une seule lecture. */
    fun hashes(file: File, isCancelled: () -> Boolean = { false }): Pair<String, String> {
        val (md5, sha) = digest(file, listOf("MD5", "SHA-256"), isCancelled)
        return md5 to sha
    }

    private fun digest(file: File, algorithms: List<String>, isCancelled: () -> Boolean): List<String> {
        val digests = algorithms.map { MessageDigest.getInstance(it) }
        val buffer = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                if (isCancelled()) throw InterruptedException()
                val read = input.read(buffer)
                if (read < 0) break
                digests.forEach { it.update(buffer, 0, read) }
            }
        }
        return digests.map { hex(it.digest()) }
    }

    /** SHA-256 des [limit] premiers octets seulement (tri rapide des doublons). */
    fun partialSha256(file: File, limit: Int = 64 * 1024): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(limit)
        file.inputStream().use { input ->
            var total = 0
            while (total < limit) {
                val read = input.read(buffer, total, limit - total)
                if (read < 0) break
                total += read
            }
            sha.update(buffer, 0, total)
        }
        return hex(sha.digest())
    }

    fun sha256(file: File, isCancelled: () -> Boolean = { false }): String = digest(file, listOf("SHA-256"), isCancelled).first()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun isSymlink(file: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
