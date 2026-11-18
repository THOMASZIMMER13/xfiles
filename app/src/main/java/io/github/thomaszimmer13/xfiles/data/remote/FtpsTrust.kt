package io.github.thomaszimmer13.xfiles.data.remote

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Vérification du certificat d'un serveur FTPS (H-01). Le gestionnaire par défaut de Commons Net ne
 * contrôle que les dates : n'importe quel certificat serait accepté.
 * - [pinned] vide : chaîne vérifiée par les autorités du téléphone (le nom d'hôte est vérifié à part).
 * - [pinned] connu : le certificat doit avoir exactement cette empreinte (certificat auto-signé
 *   accepté une première fois par l'utilisateur, comme une clé SFTP).
 * [seen] garde l'empreinte présentée, pour proposer de lui faire confiance en cas de refus.
 */
// Gestionnaire volontaire : il délègue au magasin d'autorités du système, ou exige une empreinte
// épinglée ; il ne relâche jamais la vérification (au contraire de celui de Commons Net).
@android.annotation.SuppressLint("CustomX509TrustManager")
class FtpsTrust(
    private val pinned: String,
    private val system: X509TrustManager? = systemTrustManager(),
) : X509TrustManager {
    @Volatile var seen: String? = null
        private set

    /** Vrai si le certificat présenté a été refusé (et non une autre erreur réseau). */
    @Volatile var rejected = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("certificat absent")
        val fp = fingerprint(leaf)
        seen = fp
        if (pinned.isNotEmpty()) {
            if (fp != pinned) reject("empreinte différente")
            return
        }
        try {
            (system ?: throw CertificateException("aucune autorité")).checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            rejected = true
            throw e
        }
    }

    private fun reject(why: String): Nothing {
        rejected = true
        throw CertificateException(why)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client non pris en charge")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()

    /**
     * Échec TLS traduit : empreinte à accepter (première fois) ou changée (danger) ; null pour une autre
     * erreur. [hostnameMismatch] : certificat valide mais pour un autre nom (serveur local, adresse IP).
     */
    fun hostKeyFailure(hostnameMismatch: Boolean = false): HostKeyException? {
        val fp = seen ?: return null
        if (!rejected && !hostnameMismatch) return null
        return HostKeyException(fp, changed = pinned.isNotEmpty())
    }

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

        fun systemTrustManager(): X509TrustManager? = runCatching {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        }.getOrNull()
    }
}
