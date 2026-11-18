package io.github.thomaszimmer13.xfiles.data.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 2 et 3 (smbj) : partages Windows, NAS, Samba. Le partage est le premier élément du chemin
 * de base (« Public » ou « Public/Photos »).
 */
class SmbRemote(server: RemoteServer) : RemoteClient {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build(),
    )
    private val connection: Connection
    private val session: Session
    private val share: DiskShare
    private val root: String

    init {
        val segments = server.basePath.split('/', '\\').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw RemoteException(RemoteException.Reason.NOT_FOUND, "share")
        root = segments.drop(1).joinToString("\\")
        connection = try {
            client.connect(server.host, server.port)
        } catch (e: IOException) {
            client.close()
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        }
        try {
            val auth = if (server.anonymous) AuthenticationContext.anonymous()
            else AuthenticationContext(server.username, server.password.toCharArray(), server.domain.ifBlank { null })
            session = connection.authenticate(auth)
            share = session.connectShare(segments.first()) as DiskShare
        } catch (e: Exception) {
            close()
            throw map(e)
        }
    }

    /** Chemin SMB (séparateur « \ », sans « \ » au début). */
    private fun real(path: String): String {
        val p = path.trim('/').replace('/', '\\')
        return listOf(root, p).filter { it.isNotEmpty() }.joinToString("\\")
    }

    override fun list(path: String): List<RemoteEntry> = wrap {
        share.list(real(path)).filter { RemoteClient.safeName(it.fileName.orEmpty()) != null }.map {
            val dir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            RemoteEntry(it.fileName, RemoteClient.child(path, it.fileName), dir, if (dir) 0 else it.endOfFile, it.lastWriteTime.toEpochMillis())
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) = wrap {
        share.openFile(real(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            val size = f.fileInformation.standardInformation.endOfFile
            f.inputStream.use { copyWithProgress(it, out, size, progress) }
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) = wrap {
        share.openFile(real(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
            f.outputStream.use { copyWithProgress(input, it, size, progress) }
        }
    }

    override fun mkdir(path: String) = wrap { share.mkdir(real(path)) }

    override fun rename(from: String, to: String) = wrap {
        share.open(real(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use {
            it.rename(real(to), false)
        }
    }

    override fun delete(path: String, isDirectory: Boolean): Unit = wrap {
        if (isDirectory) share.rmdir(real(path), true) else share.rm(real(path))
    }

    override val isAlive: Boolean get() = connection.isConnected && share.isConnected

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw map(e)
    }

    private companion object {
        fun map(e: Exception): RemoteException {
            if (e is SMBApiException) {
                val reason = when (e.status) {
                    NtStatus.STATUS_LOGON_FAILURE -> RemoteException.Reason.AUTH
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_NO_SUCH_FILE -> RemoteException.Reason.NOT_FOUND
                    NtStatus.STATUS_ACCESS_DENIED -> RemoteException.Reason.DENIED
                    NtStatus.STATUS_OBJECT_NAME_COLLISION -> RemoteException.Reason.EXISTS
                    else -> RemoteException.Reason.PROTOCOL
                }
                return RemoteException(reason, e.message, e)
            }
            return if (e is java.net.SocketException || e is java.net.SocketTimeoutException) RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
            else RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
        }
    }
}
