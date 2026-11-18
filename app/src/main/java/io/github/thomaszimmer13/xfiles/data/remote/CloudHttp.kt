package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Jeton d'accès d'un compte en ligne ; [invalidate] après un refus (jeton expiré ou révoqué). */
interface TokenSource {
    fun token(): String

    fun invalidate(token: String)
}

/**
 * Appels HTTP des services en ligne : jeton « Bearer », un nouvel essai avec un jeton neuf après
 * un refus 401, et les erreurs traduites en [RemoteException].
 */
internal class CloudHttp(
    private val tokens: TokenSource,
    private val errors: (code: Int, body: String, path: String) -> RemoteException,
) {
    val client: OkHttpClient = shared

    /** Exécute la requête ; la réponse renvoyée est réussie, à fermer par l'appelant. */
    fun call(path: String, retryOnAuth: Boolean = true, authorized: Boolean = true, build: Request.Builder.() -> Unit): Response {
        val token = if (authorized) tokens.token() else null
        val request = Request.Builder().apply(build).apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        val response = send(request)
        if (response.isSuccessful) return response
        val code = response.code
        val body = response.use { runCatching { it.body.string() }.getOrDefault("") }
        if (code == 401 && token != null) {
            tokens.invalidate(token)
            if (retryOnAuth) return call(path, retryOnAuth = false, authorized = true, build = build)
        }
        throw errors(code, body, path)
    }

    fun <T> use(path: String, retryOnAuth: Boolean = true, authorized: Boolean = true, build: Request.Builder.() -> Unit, read: (Response) -> T): T =
        call(path, retryOnAuth, authorized, build).use { response ->
            try {
                read(response)
            } catch (e: RemoteException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: Exception) {
                throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
            }
        }

    companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
        val OCTETS: MediaType = "application/octet-stream".toMediaType()

        /** Un seul client pour tous les comptes : connexions et fils partagés. */
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build()
        }

        fun send(request: Request, client: OkHttpClient = shared): Response = try {
            client.newCall(request).execute()
        } catch (e: InterruptedIOException) {
            // Annulation (fil interrompu) ou délai dépassé : le délai seul est une coupure réseau.
            if (e is java.net.SocketTimeoutException && !Thread.currentThread().isInterrupted) throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            throw RemoteException(RemoteException.Reason.TLS, e.message, e)
        } catch (e: java.net.UnknownHostException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: java.net.ConnectException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: java.net.NoRouteToHostException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: IOException) {
            // Coupure en cours d'échange : pas de nouvel essai automatique (un envoi serait à moitié lu).
            throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
        }

        /** Erreurs HTTP courantes des API web. */
        fun reasonOf(code: Int): RemoteException.Reason = when (code) {
            401 -> RemoteException.Reason.AUTH
            403 -> RemoteException.Reason.DENIED
            404 -> RemoteException.Reason.NOT_FOUND
            409, 412 -> RemoteException.Reason.EXISTS
            429, 500, 502, 503, 504 -> RemoteException.Reason.UNREACHABLE
            else -> RemoteException.Reason.PROTOCOL
        }

        fun parseTime(s: String?): Long = s?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
    }
}

/**
 * Corps de requête lu dans un flux : [length] octets exactement, envoyés une seule fois, avec la
 * progression du transfert entier ([offset] octets déjà envoyés sur [total]).
 */
internal class StreamBody(
    private val input: InputStream,
    private val length: Long,
    private val type: MediaType = CloudHttp.OCTETS,
    private val offset: Long = 0,
    private val total: Long = length,
    private val progress: TransferProgress? = null,
) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = length

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastReport = 0L
        while (done < length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - done).toInt())
            if (n < 0) throw IOException("fin de fichier inattendue")
            sink.write(buffer, 0, n)
            done += n
            if (progress != null && done - lastReport >= 256 * 1024) {
                lastReport = done
                progress.onProgress(offset + done, total)
            }
        }
        progress?.onProgress(offset + done, total)
    }
}
