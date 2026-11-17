package io.github.thomaszimmer13.xfiles.data.document

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Comment un fichier texte est écrit sur le disque, pour l'enregistrer à l'identique. */
data class TextFormat(val charset: Charset, val bom: Boolean, val lineEnding: String)

data class EditableText(val text: String, val format: TextFormat)

/** Lecture et écriture des fichiers texte modifiés dans l'éditeur. */
object TextFileCodec {
    /** Au-delà, l'éditeur refuse le fichier (l'édition deviendrait trop lente). */
    const val MAX_BYTES = 2L * 1024 * 1024

    private val WINDOWS_1252: Charset = charset("windows-1252")

    class TooBig : IOException("too-big")

    fun read(file: File): EditableText {
        if (file.length() > MAX_BYTES) throw TooBig()
        return decode(file.readBytes())
    }

    fun decode(bytes: ByteArray): EditableText {
        val (charset, skip) = when {
            bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            else -> null to 0
        }
        val raw = when {
            charset != null -> String(bytes, skip, bytes.size - skip, charset)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
        val used = charset ?: if (raw != null) Charsets.UTF_8 else WINDOWS_1252
        val text = raw ?: String(bytes, WINDOWS_1252)
        val ending = when {
            "\r\n" in text -> "\r\n"
            '\n' !in text && '\r' in text -> "\r"
            else -> "\n"
        }
        // Dans l'éditeur, les fins de ligne sont toujours « \n ».
        return EditableText(text.replace("\r\n", "\n").replace('\r', '\n'), TextFormat(used, skip > 0, ending))
    }

    /**
     * Octets à écrire. Si un caractère tapé n'existe pas dans l'encodage d'origine (Windows-1252),
     * le fichier passe en UTF-8 : le second élément vaut alors vrai.
     */
    fun encode(text: String, format: TextFormat): Pair<ByteArray, Boolean> {
        val content = if (format.lineEnding == "\n") text else text.replace("\n", format.lineEnding)
        var charset = format.charset
        var changed = false
        if (!charset.newEncoder().canEncode(content)) {
            charset = Charsets.UTF_8
            changed = true
        }
        val encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val buffer = encoder.encode(CharBuffer.wrap(content))
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bom = when {
            !format.bom || changed -> ByteArray(0)
            charset == Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            charset == Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        }
        return (bom + body) to changed
    }

    /** Écrit d'abord un fichier voisin puis le renomme : jamais de fichier à moitié écrit. */
    fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, ".${file.name}.xfiles-tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // Certains stockages refusent de remplacer par renommage : écriture directe.
                file.writeBytes(bytes)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
