package io.github.thomaszimmer13.xfiles.data.feedback

import android.media.AudioManager
import android.media.ToneGenerator
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Petits sons de fin d'opération, sur le volume d'accessibilité (celui de TalkBack) :
 * un bip court pour une réussite, un double bip grave pour un échec. Désactivables dans les réglages.
 */
class Feedback(preferences: PreferencesRepository) {
    @Volatile private var enabled = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch { preferences.feedbackSounds.collect { enabled = it } }
    }

    fun success() = play(ToneGenerator.TONE_PROP_ACK, 120)

    fun failure() = play(ToneGenerator.TONE_PROP_NACK, 250)

    private fun play(tone: Int, durationMs: Int) {
        if (!enabled) return
        scope.launch {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, VOLUME)
                generator.startTone(tone, durationMs)
                kotlinx.coroutines.delay(durationMs + 100L)
                generator.release()
            }
        }
    }

    private companion object {
        /** Volume relatif (0 à 100) : discret par rapport à la voix. */
        const val VOLUME = 45
    }
}
