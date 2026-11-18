package io.github.thomaszimmer13.xfiles.data.archive

import android.annotation.SuppressLint
import java.io.IOException
import java.io.InputStream

/**
 * libarchive (BSD-2, dossier « app/native ») : lecture séquentielle des RAR 4, RAR 5 et ISO 9660.
 * Remplace junrar, dont la licence UnRAR n'est pas compatible avec la GPL-3.0 (audit H-06).
 * Les RAR chiffrés sont seulement détectés : libarchive ne sait pas les déchiffrer.
 */
object LibArchive {
    const val RAR = 1
    const val ISO = 2

    /** Pour les tests seulement (tar avec des noms piégés). */
    internal const val TAR = 4

    const val TYPE_FILE = 0
    const val TYPE_DIR = 1

    /** Lien symbolique, périphérique… : jamais extrait (un lien mènerait hors du dossier). */
    const val TYPE_OTHER = 2

    /** Lien physique : même contenu que l'élément [Entry.link], déjà lu plus haut dans l'archive. */
    const val TYPE_HARDLINK = 3

    class Entry(val name: String, val type: Int, val size: Long, val time: Long, val encrypted: Boolean, val link: String?) {
        val isDirectory: Boolean get() = type == TYPE_DIR
    }

    // System.load ne sert qu'aux tests JVM (propriété posée par Gradle, absente sur Android).
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private val loaded: Boolean = run {
        // Tests JVM : bibliothèque compilée pour la machine (voir app/build.gradle.kts).
        val host = System.getProperty("xfiles.libarchive")
        runCatching { if (host != null) System.load(host) else System.loadLibrary("xfarchive") }.isSuccess
    }

    val available: Boolean get() = loaded

    /** Parcourt l'archive ; [onEntry] lit le contenu de l'élément courant dans le flux fourni. */
    fun read(path: String, password: String?, formats: Int, onEntry: (Entry, InputStream) -> Unit) {
        if (!loaded) throw IOException("libarchive absente")
        val handle = nativeOpen(path.toByteArray(Charsets.UTF_8), password?.toByteArray(Charsets.UTF_8), formats)
        try {
            val stream = EntryStream(handle)
            while (true) {
                val name = nativeNext(handle) ?: break
                val type = nativeType(handle)
                val link = if (type == TYPE_HARDLINK) nativeLink(handle)?.let { String(it, Charsets.UTF_8) } else null
                val entry = Entry(String(name, Charsets.UTF_8), type, nativeSize(handle), nativeTime(handle), nativeEncrypted(handle), link)
                onEntry(entry, stream)
            }
        } finally {
            nativeClose(handle)
        }
    }

    /** Contenu de l'élément courant ; le fermer ne ferme pas l'archive. */
    private class EntryStream(private val handle: Long) : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val n = nativeRead(handle, b, off, len)
            return if (n <= 0) -1 else n
        }

        override fun close() = Unit
    }

    @JvmStatic private external fun nativeOpen(path: ByteArray, password: ByteArray?, formats: Int): Long
    @JvmStatic private external fun nativeNext(handle: Long): ByteArray?
    @JvmStatic private external fun nativeType(handle: Long): Int
    @JvmStatic private external fun nativeSize(handle: Long): Long
    @JvmStatic private external fun nativeTime(handle: Long): Long
    @JvmStatic private external fun nativeEncrypted(handle: Long): Boolean
    @JvmStatic private external fun nativeLink(handle: Long): ByteArray?
    @JvmStatic private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    @JvmStatic private external fun nativeClose(handle: Long)
}
