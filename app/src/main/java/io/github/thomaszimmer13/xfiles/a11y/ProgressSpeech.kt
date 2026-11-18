package io.github.thomaszimmer13.xfiles.a11y

/**
 * Avancement lu par TalkBack, arrondi à la dizaine inférieure (audit M-07) : le texte ne change
 * qu'à 10 %, 20 %… pour ne pas bavarder ; la valeur exacte reste dans progressBarRangeInfo.
 */
object ProgressSpeech {
    fun tenths(percent: Int): Int = (percent.coerceIn(0, 100) / 10) * 10

    fun tenths(fraction: Float): Int = tenths((fraction.coerceIn(0f, 1f) * 100).toInt())
}
