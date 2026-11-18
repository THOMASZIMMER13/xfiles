package io.github.thomaszimmer13.xfiles.data.remote

import android.content.Context
import io.github.thomaszimmer13.xfiles.rclone.bridge.Bridge
import org.json.JSONObject
import java.io.File

/** Appels à l'API rc de rclone : JSON en entrée, JSON en sortie, erreur en [RcloneException]. */
fun interface RcloneRpc {
    fun call(method: String, input: JSONObject): JSONObject
}

/** Erreur renvoyée par rclone : message et code d'état HTTP. */
class RcloneException(val status: Int, message: String) : Exception(message)

/**
 * rclone intégré (bibliothèque Go compilée par gomobile, dossier « rclone » du dépôt) : comptes en
 * ligne (Google Drive, OneDrive, Dropbox…). Sa configuration, qui contient les jetons d'accès,
 * est chiffrée par un mot de passe lui-même chiffré par le Keystore.
 */
class Rclone(context: Context) : RcloneRpc {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "rclone")
    private val configFile = File(dir, "rclone.conf")
    private val passwordFile = File(dir, "password")

    @Volatile private var ready = false

    /**
     * Vrai si la configuration des comptes était illisible au démarrage (fichier abîmé) : rclone l'a
     * mise de côté (« rclone.conf.illisible ») au lieu d'arrêter l'application ; comptes à reconnecter.
     */
    @Volatile var configWasReset = false
        private set

    @Synchronized
    private fun init() {
        if (ready) return
        dir.mkdirs()
        val cache = File(app.cacheDir, "rclone").apply { mkdirs() }
        val password = passwordFile.takeIf { it.exists() }?.readText()?.let(SecretBox::decrypt)
        val usable = password ?: run {
            // Clé perdue (réinstallation, restauration) : configuration illisible, on repart de zéro
            // (rclone arrêterait l'application sur un mot de passe faux).
            configFile.delete()
            CloudProvider.randomPassword().also { passwordFile.writeText(SecretBox.encrypt(it)) }
        }
        configWasReset = Bridge.init(configFile.absolutePath, cache.absolutePath, usable) == Bridge.ConfigUnreadable
        ready = true
    }

    override fun call(method: String, input: JSONObject): JSONObject {
        init()
        val result = Bridge.rpc(method, input.toString())
        val output = runCatching { JSONObject(result.output) }.getOrDefault(JSONObject())
        val status = result.status.toInt()
        if (status != 200) throw RcloneException(status, output.optString("error").ifEmpty { "HTTP $status" })
        return output
    }

    /** Dossier des fichiers de passage (téléchargements et envois en cours). */
    val transferDir: File get() = File(app.cacheDir, "rclone-transfers").apply { mkdirs() }
}
