package io.github.thomaszimmer13.xfiles.data.security

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Verrouillage d'XFiles par l'empreinte, le visage ou le code du téléphone, et confirmation avant
 * d'ouvrir un serveur ou un compte. Réglages lus sans attendre (dès le démarrage).
 */
class AppLock(context: Context, private val onLongAbsence: () -> Unit) {
    private val prefs = context.applicationContext.getSharedPreferences("securite", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _delay = MutableStateFlow(prefs.getInt(KEY_DELAY, 60))
    /** Secondes en arrière-plan avant de reverrouiller (0 : dès la sortie). */
    val delay: StateFlow<Int> = _delay.asStateFlow()

    private val _protectServers = MutableStateFlow(prefs.getBoolean(KEY_SERVERS, false))
    val protectServers: StateFlow<Boolean> = _protectServers.asStateFlow()

    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _serversConfirmed = MutableStateFlow(false)
    val serversConfirmed: StateFlow<Boolean> = _serversConfirmed.asStateFlow()

    private var backgroundAt = 0L

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (!value) _locked.value = false
    }

    fun setDelay(seconds: Int) {
        prefs.edit().putInt(KEY_DELAY, seconds).apply()
        _delay.value = seconds
    }

    fun setProtectServers(value: Boolean) {
        prefs.edit().putBoolean(KEY_SERVERS, value).apply()
        _protectServers.value = value
        if (value) _serversConfirmed.value = true // vient d'être confirmé pour l'activer
    }

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (backgroundAt == 0L) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        backgroundAt = 0L
        if (away >= _delay.value * 1000L) {
            if (_enabled.value) _locked.value = true
            _serversConfirmed.value = false
        }
        if (away >= VAULT_IDLE_MS || (_enabled.value && _locked.value)) onLongAbsence()
    }

    /** Identité confirmée : l'app et les serveurs sont ouverts. */
    fun unlocked() {
        _locked.value = false
        _serversConfirmed.value = true
    }

    fun confirmServers() {
        _serversConfirmed.value = true
    }

    companion object {
        private const val KEY_ENABLED = "verrou"
        private const val KEY_DELAY = "verrou_delai"
        private const val KEY_SERVERS = "verrou_serveurs"

        /** Coffres refermés après cinq minutes hors de l'application. */
        const val VAULT_IDLE_MS = 5 * 60_000L
        val DELAYS = listOf(0, 60, 300, 900)
    }
}
