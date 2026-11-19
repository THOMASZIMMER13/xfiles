package io.github.thomaszimmer13.xfiles.data.vault

import io.github.thomaszimmer13.xfiles.data.fs.deleteRecursivelyNoFollow
import io.github.thomaszimmer13.xfiles.data.fs.Ephemeral
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.thomaszimmer13.xfiles.data.remote.Protocol
import io.github.thomaszimmer13.xfiles.data.remote.Rclone
import io.github.thomaszimmer13.xfiles.data.remote.RcloneException
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import io.github.thomaszimmer13.xfiles.data.remote.RemoteSessions
import io.github.thomaszimmer13.xfiles.data.remote.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Clés des coffres ouverts (mot de passe obscurci par rclone), en mémoire seulement. */
class VaultKeys {
    private val _open = MutableStateFlow<Map<String, String>>(emptyMap())
    val open: StateFlow<Map<String, String>> = _open.asStateFlow()

    fun get(id: String): String? = _open.value[id]

    fun put(id: String, key: String) = _open.update { it + (id to key) }

    fun remove(id: String) = _open.update { it - id }

    fun clear() = _open.update { emptyMap() }
}

/**
 * Coffres chiffrés par rclone « crypt » (contenus et noms) : sur le téléphone ou dans un compte en
 * ligne, compatibles avec rclone sur ordinateur. Un coffre est un [RemoteServer] de type
 * [Protocol.VAULT] : host = emplacement des fichiers chiffrés (chemin, ou « compte:dossier »),
 * username = compte en ligne utilisé (vide : téléphone), password = mot de passe scellé par
 * l'empreinte (vide : pas d'empreinte). Le mot de passe lui-même n'est jamais enregistré en clair.
 */
class Vaults(
    context: Context,
    private val rclone: Rclone,
    private val store: ServerStore,
    private val sessions: RemoteSessions,
    val keys: VaultKeys,
    /** Appelé à chaque fermeture : oublie les Récents et positions des copies de passage (N-05). */
    private val onLocked: () -> Unit = {},
) {
    private val cacheDir: File = context.applicationContext.cacheDir
    private val cacheRoot = File(cacheDir, "remote")

    /** Fichiers de passage de rclone (même dossier que Rclone.transferDir) : en clair pour un coffre. */
    private val transferRoot = File(context.applicationContext.cacheDir, "rclone-transfers")

    fun isOpen(id: String) = keys.get(id) != null

    /** Emplacement sur le téléphone : Documents/Coffres XFiles/<nom>. */
    fun phoneLocation(name: String): String =
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "$FOLDER/$name").absolutePath

    fun cloudLocation(remoteName: String, name: String): String = "$remoteName:$FOLDER/$name"

    private fun obscure(password: String): String = rclone.call("core/obscure", JSONObject().put("clear", password)).getString("obscured")

    /** Vrai si l'emplacement contient déjà des fichiers. */
    private fun occupied(location: String): Boolean = try {
        (rclone.call("operations/list", JSONObject().put("fs", location).put("remote", "").put("opt", JSONObject())).optJSONArray("list")?.length() ?: 0) > 0
    } catch (_: RcloneException) {
        false // dossier absent
    }

    /** Crée le coffre et le laisse ouvert. */
    suspend fun create(name: String, location: String, cloudServerId: String, password: String): RemoteServer = withContext(Dispatchers.IO) {
        if (occupied(location)) throw RemoteException(RemoteException.Reason.EXISTS, location)
        val server = RemoteServer(ID_PREFIX + UUID.randomUUID(), Protocol.VAULT, name, location, username = cloudServerId)
        keys.put(server.id, obscure(password))
        try {
            sessions.use(server) { it.upload(ByteArrayInputStream(WITNESS), WITNESS.size.toLong(), "/$WITNESS_NAME") }
            store.save(server)
        } catch (e: Exception) {
            keys.remove(server.id)
            sessions.forget(server.id)
            throw e
        }
        server
    }

    /** Ouvre le coffre ; faux si le mot de passe ne correspond pas. */
    suspend fun unlock(server: RemoteServer, password: String): Boolean = withContext(Dispatchers.IO) {
        keys.put(server.id, obscure(password))
        sessions.forget(server.id)
        val ok = try {
            val out = ByteArrayOutputStream()
            sessions.use(server) { it.download("/$WITNESS_NAME", out) }
            out.toByteArray().contentEquals(WITNESS)
        } catch (e: RemoteException) {
            // Audit F-07 : seul un témoin introuvable ou indéchiffrable signale un mauvais mot de passe ;
            // une panne réseau ou de protocole est remontée telle quelle (et non lue « mot de passe incorrect »).
            if (!wrongPassword(e)) {
                keys.remove(server.id)
                sessions.forget(server.id)
                throw e
            }
            false
        }
        if (!ok) lock(server.id)
        ok
    }

    /** Ferme le coffre et efface les copies déchiffrées ouvertes depuis lui. */
    fun lock(id: String) {
        keys.remove(id)
        sessions.forget(id)
        deleteRecursivelyNoFollow(File(cacheRoot, id))
        // Texte OCR, aperçus d'archives et traces mémorisées des copies de passage (N-05).
        Ephemeral.purge(cacheDir)
        onLocked()
    }

    fun lockAll() {
        keys.open.value.keys.forEach(::lock)
        purgeCache(atStartup = false)
    }

    /**
     * Copies déchiffrées laissées par un arrêt de l'application (H-04) : fichiers ouverts depuis un
     * coffre fermé et, au démarrage seulement (aucun transfert ne peut être en cours), tous les
     * fichiers de passage de rclone, qui peuvent contenir le contenu en clair d'un coffre.
     */
    fun purgeCache(atStartup: Boolean = true) {
        cacheRoot.listFiles()?.filter { it.name.startsWith(ID_PREFIX) && !isOpen(it.name) }?.forEach { deleteRecursivelyNoFollow(it) }
        if (atStartup) transferRoot.listFiles()?.forEach { deleteRecursivelyNoFollow(it) }
        Ephemeral.purge(cacheDir)
        onLocked()
    }

    /** Retire le coffre de la liste (ses fichiers chiffrés restent en place). */
    suspend fun remove(server: RemoteServer) {
        lock(server.id)
        deleteBioKey(server.id)
        store.delete(server.id)
    }

    // --- Empreinte : le mot de passe est chiffré par une clé du Keystore qui exige l'empreinte. ---

    private fun alias(id: String) = "xfiles-vault-$id"

    private fun bioKey(id: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias(id), null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteBioKey(id: String) {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(id)) }
    }

    /** Chiffrement à autoriser par l'empreinte, pour sceller le mot de passe. */
    fun sealCipher(id: String): Cipher = freshAfterInvalidation({ encryptCipher(id) }, { deleteBioKey(id) })

    private fun encryptCipher(id: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, bioKey(id)) }

    /** Déchiffrement à autoriser par l'empreinte ; null si les empreintes ont changé (scellé perdu). */
    fun openCipher(server: RemoteServer): Cipher? = runCatching {
        val data = Base64.decode(server.password, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, bioKey(server.id), GCMParameterSpec(128, data, 0, 12)) }
    }.getOrNull()

    suspend fun saveSeal(server: RemoteServer, cipher: Cipher, password: String): RemoteServer {
        val sealed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val updated = server.copy(password = Base64.encodeToString(sealed, Base64.NO_WRAP))
        store.save(updated)
        return updated
    }

    fun openSeal(server: RemoteServer, cipher: Cipher): String? = runCatching {
        val data = Base64.decode(server.password, Base64.NO_WRAP)
        String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }.getOrNull()

    suspend fun forgetSeal(server: RemoteServer): RemoteServer {
        deleteBioKey(server.id)
        return server.copy(password = "").also { store.save(it) }
    }

    companion object {
        const val ID_PREFIX = "vault-"
        const val FOLDER = "Coffres XFiles"
        private const val WITNESS_NAME = ".xfiles-coffre"

        /**
         * Empreintes ajoutées ou retirées : la clé du téléphone est morte pour toujours ; on l'efface
         * ([reset]) et on en crée une neuve, une seule fois (M-03).
         */
        internal fun <T> freshAfterInvalidation(create: () -> T, reset: () -> Unit): T = try {
            create()
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) {
            reset()
            create()
        }

        /** Erreur de lecture du témoin due à la clé : nom chiffré introuvable, ou bloc indéchiffrable. */
        internal fun wrongPassword(e: RemoteException): Boolean {
            if (e.reason == RemoteException.Reason.NOT_FOUND) return true
            if (e.reason != RemoteException.Reason.PROTOCOL) return false
            val m = e.message.orEmpty().lowercase()
            return "bad password" in m || "authenticate decrypted" in m || "decrypt" in m
        }
        private val WITNESS = "xfiles-vault-1".toByteArray()

        /** Valeur rclone entre apostrophes (doublées à l'intérieur). */
        private fun quote(s: String) = "'" + s.replace("'", "''") + "'"

        /** Nom rclone du coffre ouvert : remote « crypt » à la volée, sans rien dans la configuration. */
        fun remoteOf(server: RemoteServer, key: String) = ":crypt,remote=${quote(server.host)},password=${quote(key)}"
    }
}
