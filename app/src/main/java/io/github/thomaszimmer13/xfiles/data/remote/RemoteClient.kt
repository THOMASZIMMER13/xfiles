package io.github.thomaszimmer13.xfiles.data.remote

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class Protocol(val defaultPort: Int, val scheme: String, val cloud: Boolean = false) {
    SMB(445, "smb"),
    SFTP(22, "sftp"),
    FTP(21, "ftp"),

    /** FTP chiffré (FTPS explicite, AUTH TLS). */
    FTPS(21, "ftps"),
    WEBDAV(443, "webdav"),

    /** Stockage en ligne : compte connecté par OAuth, jeton de renouvellement dans le mot de passe. */
    GDRIVE(443, "googledrive", cloud = true),
    DROPBOX(443, "dropbox", cloud = true),
    ONEDRIVE(443, "onedrive", cloud = true),
}

/**
 * Un serveur enregistré. [basePath] : partage SMB (« Public »), dossier de départ SFTP/FTP,
 * ou chemin WebDAV (« /remote.php/dav/files/thomas » pour Nextcloud).
 */
data class RemoteServer(
    val id: String,
    val protocol: Protocol,
    val name: String,
    val host: String,
    val port: Int = protocol.defaultPort,
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    /** SMB : domaine ou groupe de travail (souvent vide). */
    val domain: String = "",
    /** WebDAV : https (sinon http). */
    val secure: Boolean = true,
    /** SFTP : empreinte de la clé du serveur acceptée la première fois. */
    val hostKey: String = "",
) {
    val anonymous: Boolean get() = username.isBlank()
    val displayAddress: String get() = if (protocol.cloud) username else "${protocol.scheme}://$host" + (if (port != protocol.defaultPort) ":$port" else "") + basePath.let { if (it.isEmpty() || it.startsWith("/")) it else "/$it" }
}

/** Élément d'un dossier distant. [path] : chemin absolu sur le serveur, « /Photos/2026/a.jpg ». */
data class RemoteEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/** Échec d'une opération distante, avec une raison que l'interface sait annoncer. */
class RemoteException(val reason: Reason, message: String? = null, cause: Throwable? = null) : IOException(message ?: reason.name, cause) {
    enum class Reason { UNREACHABLE, AUTH, NOT_FOUND, DENIED, EXISTS, TLS, PROTOCOL }
}

/** Clé SFTP inconnue (première connexion) ou changée : l'utilisateur doit décider. */
class HostKeyException(val fingerprint: String, val changed: Boolean) : IOException("host key $fingerprint")

/** Progression d'un transfert : octets faits (le total peut être inconnu, -1). */
fun interface TransferProgress {
    fun onProgress(done: Long, total: Long)
}

/**
 * Accès à un serveur : une connexion, utilisée par une opération à la fois (voir RemoteSessions).
 * Les chemins sont absolus côté serveur et commencent par « / ».
 */
interface RemoteClient : Closeable {
    fun list(path: String): List<RemoteEntry>

    fun download(path: String, out: OutputStream, progress: TransferProgress? = null)

    fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress? = null)

    fun mkdir(path: String)

    fun rename(from: String, to: String)

    /** Supprime un fichier, ou un dossier et tout son contenu. */
    fun delete(path: String, isDirectory: Boolean)

    /** Vrai si la connexion est encore utilisable. */
    val isAlive: Boolean

    companion object {
        fun connect(server: RemoteServer, cloud: CloudAuth? = null): RemoteClient = when (server.protocol) {
            Protocol.SFTP -> SftpRemote(server)
            Protocol.FTP, Protocol.FTPS -> FtpRemote(server)
            Protocol.WEBDAV -> WebDavRemote(server)
            Protocol.SMB -> SmbRemote(server)
            Protocol.GDRIVE -> GoogleDriveRemote(requireNotNull(cloud).tokens(server))
            Protocol.DROPBOX -> DropboxRemote(requireNotNull(cloud).tokens(server))
            Protocol.ONEDRIVE -> OneDriveRemote(requireNotNull(cloud).tokens(server))
        }

        /** « /a/b » + « c » → « /a/b/c ». */
        fun child(parent: String, name: String) = if (parent.endsWith("/")) parent + name else "$parent/$name"

        fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

        fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
    }
}

/** Copie d'un flux avec progression, interrompue si le fil est interrompu (annulation). */
internal fun copyWithProgress(input: InputStream, out: OutputStream, total: Long, progress: TransferProgress?) {
    val buffer = ByteArray(64 * 1024)
    var done = 0L
    var lastReport = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        done += n
        if (progress != null && done - lastReport >= 256 * 1024) {
            lastReport = done
            progress.onProgress(done, total)
        }
    }
    progress?.onProgress(done, total)
}
