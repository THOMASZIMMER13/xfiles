package io.github.thomaszimmer13.xfiles.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Fenêtre d'identification d'Android (empreinte, visage ou code), lue par TalkBack. */
object Biometrics {
    /** Verrouillage de l'app et des serveurs : tout moyen du téléphone, code compris. */
    const val ANY = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Coffres : empreinte (ou visage sûr) seulement, la clé du Keystore l'exige. */
    const val STRONG = BIOMETRIC_STRONG

    /**
     * Le téléphone a-t-il un verrouillage d'écran (code, schéma, mot de passe) ? C'est le seul critère
     * pour renoncer au verrouillage d'XFiles (M-02) : un capteur momentanément indisponible ne compte
     * pas, la fenêtre d'Android propose alors le code.
     */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    fun available(context: Context, authenticators: Int): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun activity(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    /** Résultat, ou null si annulé ou refusé. [negative] : bouton d'abandon (sans code du téléphone). */
    suspend fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        authenticators: Int = ANY,
        crypto: BiometricPrompt.CryptoObject? = null,
        negative: String? = null,
    ): BiometricPrompt.AuthenticationResult? = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(authenticators)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText(negative ?: "Annuler") }
            .setConfirmationRequired(false)
            .build()
        try {
            if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        } catch (e: IllegalStateException) {
            // Activité pas encore prête (retour au premier plan) : l'utilisateur touchera le bouton.
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
    }
}
