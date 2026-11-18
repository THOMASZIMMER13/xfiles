package io.github.thomaszimmer13.xfiles.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    fun get(id: String): RemoteServer? = _servers.value.firstOrNull { it.id == id }

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

    // --- Chiffrement (AES-GCM, clé du Keystore) ---

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String = runCatching {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrDefault("")

    private companion object {
        const val KEY_ALIAS = "xfiles-servers"
    }
}
