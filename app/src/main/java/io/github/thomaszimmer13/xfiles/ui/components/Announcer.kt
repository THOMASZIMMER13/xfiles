package io.github.thomaszimmer13.xfiles.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Messages d'état à faire lire par TalkBack via [StatusMessage].
 * Chaque message s'efface après quelques secondes.
 */
class Announcer(private val scope: CoroutineScope) {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var clearJob: Job? = null
    private var toggle = false

    fun announce(text: String) {
        clearJob?.cancel()
        // Un même message répété doit être relu : on alterne un espace invisible (non prononcé)
        // pour que le texte change à chaque fois, sinon TalkBack ne détecte aucun changement.
        toggle = !toggle
        _message.value = if (toggle) text else "$text​"
        clearJob = scope.launch {
            delay(DURATION_MS)
            _message.value = null
        }
    }

    private companion object {
        const val DURATION_MS = 10_000L
    }
}
