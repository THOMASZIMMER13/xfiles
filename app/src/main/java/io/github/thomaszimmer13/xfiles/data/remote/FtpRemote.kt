package io.github.thomaszimmer13.xfiles.data.remote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration

/** FTP et FTPS explicite (Apache Commons Net), mode passif, binaire, UTF-8. */
class FtpRemote(server: RemoteServer) : RemoteClient {
    private val ftp: FTPClient = if (server.protocol == Protocol.FTPS) FTPSClient(false) else FTPClient()
    private val root = server.basePath.trim().trimEnd('/')

    init {
        ftp.controlEncoding = "UTF-8"
        ftp.connectTimeout = 15_000
        ftp.setDefaultTimeout(30_000)
        ftp.setDataTimeout(Duration.ofSeconds(60))
        try {
            ftp.connect(server.host, server.port)
        } catch (e: UnknownHostException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: ConnectException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: javax.net.ssl.SSLException) {
            throw RemoteException(RemoteException.Reason.TLS, e.message, e)
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        }
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            close()
            throw RemoteException(RemoteException.Reason.PROTOCOL, ftp.replyString)
        }
        val user = server.username.ifBlank { "anonymous" }
        val pass = if (server.username.isBlank()) "xfiles@" else server.password
        if (!ftp.login(user, pass)) {
            close()
            throw RemoteException(RemoteException.Reason.AUTH, ftp.replyString)
        }
        if (ftp is FTPSClient) {
            ftp.execPBSZ(0)
            ftp.execPROT("P")
        }
        runCatching { ftp.sendCommand("OPTS UTF8 ON") }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
    }

    private fun real(path: String) = (root + path).ifEmpty { "/" }

    override fun list(path: String): List<RemoteEntry> = wrap {
        val files: Array<FTPFile> = try {
            ftp.mlistDir(real(path)) ?: emptyArray()
        } catch (_: IOException) {
            emptyArray()
        }.ifEmpty { if (FTPReply.isPositiveCompletion(ftp.replyCode)) emptyArray() else ftp.listFiles(real(path)) }
        check(path)
        files.filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }.map {
            RemoteEntry(it.name, RemoteClient.child(path, it.name), it.isDirectory, it.size.coerceAtLeast(0), it.timestamp?.timeInMillis ?: 0L)
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) = wrap {
        val size = runCatching { ftp.mlistFile(real(path))?.size ?: -1L }.getOrDefault(-1L)
        val input = ftp.retrieveFileStream(real(path)) ?: throw failure(path)
        input.use { copyWithProgress(it, out, size, progress) }
        if (!ftp.completePendingCommand()) throw failure(path)
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) = wrap {
        val out = ftp.storeFileStream(real(path)) ?: throw failure(path)
        out.use { copyWithProgress(input, it, size, progress) }
        if (!ftp.completePendingCommand()) throw failure(path)
    }

    override fun mkdir(path: String) = wrap { if (!ftp.makeDirectory(real(path))) throw failure(path) }

    override fun rename(from: String, to: String) = wrap { if (!ftp.rename(real(from), real(to))) throw failure(from) }

    override fun delete(path: String, isDirectory: Boolean): Unit = wrap {
        if (!isDirectory) {
            if (!ftp.deleteFile(real(path))) throw failure(path)
        } else {
            for (e in list(path)) delete(e.path, e.isDirectory)
            if (!ftp.removeDirectory(real(path))) throw failure(path)
        }
    }

    override val isAlive: Boolean get() = ftp.isConnected && runCatching { ftp.sendNoOp() }.getOrDefault(false)

    override fun close() {
        runCatching { if (ftp.isConnected) ftp.logout() }
        runCatching { ftp.disconnect() }
    }

    private fun check(path: String) {
        val code = ftp.replyCode
        if (code == 550) throw RemoteException(RemoteException.Reason.NOT_FOUND, path)
    }

    private fun failure(path: String): RemoteException {
        val code = ftp.replyCode
        val reason = when (code) {
            550 -> if (ftp.replyString.contains("exist", ignoreCase = true) && !ftp.replyString.contains("not", ignoreCase = true)) RemoteException.Reason.EXISTS else RemoteException.Reason.NOT_FOUND
            530, 532 -> RemoteException.Reason.DENIED
            553 -> RemoteException.Reason.DENIED
            else -> RemoteException.Reason.PROTOCOL
        }
        return RemoteException(reason, "$path : ${ftp.replyString.trim()}")
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw e
    } catch (e: java.net.SocketException) {
        throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
    } catch (e: java.net.SocketTimeoutException) {
        throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
    } catch (e: IOException) {
        throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
    }
}
