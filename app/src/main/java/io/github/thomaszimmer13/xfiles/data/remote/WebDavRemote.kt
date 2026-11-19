package io.github.thomaszimmer13.xfiles.data.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV (Nextcloud, ownCloud, NAS…) avec OkHttp : PROPFIND, GET, PUT, MKCOL, MOVE, DELETE. */
class WebDavRemote(private val server: RemoteServer) : RemoteClient {
    private val base: HttpUrl = HttpUrl.Builder()
        .scheme(if (server.secure) "https" else "http")
        .host(server.host)
        .port(server.port)
        .apply { server.basePath.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
        .build()
    private val basePathPrefix = base.encodedPath.trimEnd('/')

    init {
        // HTTP en clair : seulement vers le réseau local (N-06), Internet exige HTTPS.
        if (!server.secure && !LocalNetwork.isLocalHost(server.host)) {
            throw RemoteException(RemoteException.Reason.CLEARTEXT, server.host)
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply {
            if (!server.anonymous) {
                val credentials = Credentials.basic(server.username, server.password, Charsets.UTF_8)
                addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", credentials).build()) }
            }
        }
        .build()

    init {
        // Vérifie l'accès dès la connexion (identifiants, adresse).
        list("/")
    }

    private fun url(path: String): HttpUrl = base.newBuilder().apply {
        path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
    }.build()

    private fun dirUrl(path: String): HttpUrl = url(path).let { if (it.encodedPath.endsWith("/")) it else it.newBuilder().addPathSegment("").build() }

    override fun list(path: String): List<RemoteEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val request = Request.Builder().url(dirUrl(path)).header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        return execute(request, path) { response ->
            parseMultistatus(response.body.byteStream(), path, dirUrl(path).encodedPath)
        }
    }

    override fun download(path: String, out: OutputStream, progress: TransferProgress?) {
        execute(Request.Builder().url(url(path)).get().build(), path) { response ->
            val total = response.body.contentLength()
            response.body.byteStream().use { copyWithProgress(it, out, total, progress) }
        }
    }

    override fun upload(input: InputStream, size: Long, path: String, progress: TransferProgress?) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = copyWithProgress(input, sink.outputStream(), size, progress)
        }
        execute(Request.Builder().url(url(path)).put(body).build(), path) { }
    }

    override fun mkdir(path: String) {
        execute(Request.Builder().url(dirUrl(path)).method("MKCOL", null).build(), path) { }
    }

    override fun rename(from: String, to: String) {
        val request = Request.Builder().url(url(from)).method("MOVE", null)
            .header("Destination", url(to).toString()).header("Overwrite", "F").build()
        execute(request, from) { }
    }

    override fun delete(path: String, isDirectory: Boolean) {
        execute(Request.Builder().url(if (isDirectory) dirUrl(path) else url(path)).delete().build(), path) { }
    }

    override val isAlive: Boolean get() = true

    override fun close() {
        http.connectionPool.evictAll()
    }

    private fun <T> execute(request: Request, path: String, block: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: javax.net.ssl.SSLException) {
            throw RemoteException(RemoteException.Reason.TLS, e.message, e)
        } catch (e: java.net.UnknownHostException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: java.net.ConnectException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: java.net.SocketTimeoutException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, e.message, e)
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
        }
        return response.use {
            if (!it.isSuccessful) {
                val reason = when (it.code) {
                    401 -> RemoteException.Reason.AUTH
                    403 -> RemoteException.Reason.DENIED
                    404, 409 -> RemoteException.Reason.NOT_FOUND
                    405, 412 -> RemoteException.Reason.EXISTS
                    else -> RemoteException.Reason.PROTOCOL
                }
                throw RemoteException(reason, "$path : HTTP ${it.code}")
            }
            try {
                block(it)
            } catch (e: RemoteException) {
                throw e
            } catch (e: Exception) {
                throw RemoteException(RemoteException.Reason.PROTOCOL, e.message, e)
            }
        }
    }

    internal companion object {
        /**
         * Réponse PROPFIND (multistatus) : les éléments de [path], sans le dossier lui-même. Les href
         * peuvent être relatifs (« /dav/a.txt ») ou absolus (« https://nas.local/dav/a.txt », N-02).
         */
        fun parseMultistatus(xml: java.io.InputStream, path: String, selfEncodedPath: String): List<RemoteEntry> {
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Réponse d'un serveur : ni DTD ni entités externes (XXE).
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(xml)
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            val self = selfEncodedPath.trimEnd('/')
            return (0 until responses.length).mapNotNull { i ->
                val r = responses.item(i) as Element
                val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: return@mapNotNull null
                val hrefPath = (href.toHttpUrlOrNull()?.encodedPath ?: href).trimEnd('/')
                if (hrefPath == self || hrefPath.isEmpty()) return@mapNotNull null
                // « + » est un caractère ordinaire dans un chemin d'URL (URLDecoder le lirait comme une espace).
                val raw = hrefPath.substringAfterLast('/').replace("+", "%2B")
                val name = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.let(RemoteClient::safeName) ?: return@mapNotNull null
                val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: -1L
                val modified = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim()?.let(::parseDate) ?: 0L
                RemoteEntry(name, RemoteClient.child(path, name), isDir, if (isDir) 0 else size, modified)
            }
        }

        fun parseDate(s: String): Long? = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time
        }.getOrNull()
    }
}
