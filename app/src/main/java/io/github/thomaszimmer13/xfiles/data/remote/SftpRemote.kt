package io.github.thomaszimmer13.xfiles.data.remote

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/**
 * SFTP (sshj). Clé du serveur : acceptée la première fois après accord (empreinte SHA-256 annoncée),
 * puis vérifiée à chaque connexion ; si elle change, la connexion est refusée.
 */
class SftpRemote(server: RemoteServer) : RemoteClient {
    private val ssh = SSHClient(DefaultConfig())
    private val sftp: SFTPClient
    private val root: String

    init {
        var seen: String? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                seen = fp
                return server.hostKey.isNotEmpty() && server.hostKey == fp
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        try {
            ssh.connect(server.host, server.port)
        } catch (e: TransportException) {
            ssh.close()
            val fp = seen
            if (fp != null && fp != server.hostKey) throw HostKeyException(fp, changed = server.hostKey.isNotEmpty())
            throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
        } catch (e: UnknownHostException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: ConnectException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: java.net.SocketTimeoutException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        }
        try {
            ssh.authPassword(server.username, server.password)
            sftp = ssh.newSFTPClient()
            root = server.basePath.trim().ifEmpty { sftp.canonicalize(".") }.trimEnd('/')
        } catch (e: UserAuthException) {
            ssh.close()
            throw RemoteException(RemoteException.Reason.AUTH, e.message, e)
        } catch (e: Exception) {
            ssh.close()
            throw map(e)
        }
    }

    private fun real(path: String) = (root + path).ifEmpty { "/" }

    override fun list(path: String): List<RemoteEntry> = wrap {
        sftp.ls(real(path)).filter { it.name != "." && it.name != ".." }.map {
            val a = it.attributes
            RemoteEntry(it.name, RemoteClient.child(path, it.name), a.type == FileMode.Type.DIRECTORY, a.size, a.mtime * 1000)
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) = wrap {
        sftp.open(real(path)).use { file ->
            val size = file.length()
            file.ReadAheadRemoteFileInputStream(16).use { copyWithProgress(it, out, size, progress) }
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) = wrap {
        sftp.open(real(path), EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.RemoteFileOutputStream(0, 16).use { copyWithProgress(input, it, size, progress) }
        }
    }

    override fun mkdir(path: String) = wrap { sftp.mkdir(real(path)) }

    override fun rename(from: String, to: String) = wrap { sftp.rename(real(from), real(to)) }

    override fun delete(path: String, isDirectory: Boolean): Unit = wrap {
        if (!isDirectory) {
            sftp.rm(real(path))
        } else {
            for (e in list(path)) delete(e.path, e.isDirectory)
            sftp.rmdir(real(path))
        }
    }

    override val isAlive: Boolean get() = ssh.isConnected && ssh.isAuthenticated

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.close() }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw map(e)
    }

    companion object {
        /** Empreinte OpenSSH : « SHA256:… » (base64 sans remplissage). */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        private fun map(e: Exception): RemoteException = when {
            e is SFTPException && e.statusCode == Response.StatusCode.NO_SUCH_FILE -> RemoteException(RemoteException.Reason.NOT_FOUND, e.message, e)
            e is SFTPException && e.statusCode == Response.StatusCode.PERMISSION_DENIED -> RemoteException(RemoteException.Reason.DENIED, e.message, e)
            e is SFTPException && e.statusCode == Response.StatusCode.FILE_ALREADY_EXISTS -> RemoteException(RemoteException.Reason.EXISTS, e.message, e)
            e is java.net.SocketException || e is java.net.SocketTimeoutException -> RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
            else -> RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
        }
    }
}
