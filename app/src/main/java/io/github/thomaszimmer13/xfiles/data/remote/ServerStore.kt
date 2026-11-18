package io.github.thomaszimmer13.xfiles.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Serveurs enregistrés (fichier privé de l'app). Les mots de passe sont chiffrés par une clé du
 * Keystore Android, qui ne quitte jamais le téléphone ; ils sont exclus des sauvegardes.
 */
class ServerStore(context: Context) {
    private val file = File(context.filesDir, "servers.json")
    private val mutex = Mutex()
    private val _servers = MutableStateFlow<List<RemoteServer>>(emptyList())
    val servers: StateFlow<List<RemoteServer>> = _servers.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { _servers.value = read() }
    }

    /** Serveur enregistré, ou le dossier Android par Shizuku (virtuel). */
    fun get(id: String): RemoteServer? =
        if (id == io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.SERVER_ID) shizukuServer
        else _servers.value.firstOrNull { it.id == id }

    private val shizukuServer = RemoteServer(
        io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.SERVER_ID,
        Protocol.SHIZUKU,
        context.getString(io.github.thomaszimmer13.xfiles.R.string.shizuku_title),
        io.github.thomaszimmer13.xfiles.data.shizuku.ShizukuAccess.ROOT,
    )

    suspend fun save(server: RemoteServer) = edit { list ->
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list.toMutableList().also { it[i] = server } else list + server
    }

    suspend fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    private suspend fun edit(change: (List<RemoteServer>) -> List<RemoteServer>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = change(read())
            write(updated)
            _servers.value = updated
        }
    }

    private fun read(): List<RemoteServer> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                runCatching {
                    RemoteServer(
                        id = o.getString("id"),
                        protocol = Protocol.valueOf(o.getString("protocol")),
                        name = o.optString("name"),
                        host = o.getString("host"),
                        port = o.optInt("port"),
                        basePath = o.optString("basePath"),
                        username = o.optString("username"),
                        password = o.optString("secret").takeIf { it.isNotEmpty() }?.let(::decrypt).orEmpty(),
                        domain = o.optString("domain"),
                        secure = o.optBoolean("secure", true),
                        hostKey = o.optString("hostKey"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<RemoteServer>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("protocol", s.protocol.name)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("basePath", s.basePath)
                    .put("username", s.username)
                    .put("secret", if (s.password.isEmpty()) "" else encrypt(s.password))
                    .put("domain", s.domain)
                    .put("secure", s.secure)
                    .put("hostKey", s.hostKey),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    private fun encrypt(plain: String): String = SecretBox.encrypt(plain)

    private fun decrypt(encoded: String): String = SecretBox.decrypt(encoded).orEmpty()

}
