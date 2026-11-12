package io.github.thomaszimmer13.xfiles.a11y

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Dates de modification :
 * - [spoken] pour TalkBack : « modifié aujourd'hui à 9 h 05 », « modifié le 1er mars 2024 » ;
 * - [short] pour l'affichage : « Aujourd'hui 09:05 », « 3 mars », « 3 mars 2024 ».
 */
object DateFormatter {

    private val locale = Locale.FRANCE

    fun spoken(millis: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        val date = Instant.ofEpochMilli(millis).atZone(now.zone)
        val time = "${date.hour} h ${"%02d".format(locale, date.minute)}"
        return when (daysBetween(date, now)) {
            0L -> "modifié aujourd'hui à $time"
            1L -> "modifié hier à $time"
            else -> if (date.year == now.year) {
                "modifié le ${dayMonth(date)} à $time"
            } else {
                "modifié le ${dayMonth(date)} ${date.year}"
            }
        }
    }

    fun short(millis: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        val date = Instant.ofEpochMilli(millis).atZone(now.zone)
        val time = "%02d:%02d".format(locale, date.hour, date.minute)
        return when (daysBetween(date, now)) {
            0L -> "Aujourd'hui $time"
            1L -> "Hier $time"
            else -> if (date.year == now.year) dayMonth(date) else "${dayMonth(date)} ${date.year}"
        }
    }

    /** Date complète, pour les détails : « jeudi 12 novembre 2026 à 9 h 05 ». */
    fun full(millis: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        val date = Instant.ofEpochMilli(millis).atZone(now.zone)
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        return "$weekday ${dayMonth(date)} ${date.year} à ${date.hour} h ${"%02d".format(locale, date.minute)}"
    }

    private fun daysBetween(date: ZonedDateTime, now: ZonedDateTime): Long =
        ChronoUnit.DAYS.between(date.toLocalDate(), now.toLocalDate())

    /** « 1er mars », « 3 mars ». */
    private fun dayMonth(date: ZonedDateTime): String {
        val day = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()
        return "$day ${date.month.getDisplayName(TextStyle.FULL, locale)}"
    }
}
