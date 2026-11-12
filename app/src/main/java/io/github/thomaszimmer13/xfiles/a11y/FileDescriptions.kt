package io.github.thomaszimmer13.xfiles.a11y

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import java.time.ZonedDateTime

/**
 * Texte d'une ligne de fichier ou de dossier.
 * TalkBack lit, dans cet ordre : nom, type, taille (ou nombre d'éléments), date.
 */
object FileDescriptions {

    fun spoken(item: FileItem, now: ZonedDateTime = ZonedDateTime.now()): String = listOfNotNull(
        item.name,
        FileTypeDescriber.describe(item.name, item.isDirectory),
        if (item.isDirectory) item.childCount?.let(::itemCount) else SizeFormatter.spoken(item.sizeBytes),
        DateFormatter.spoken(item.lastModified, now),
    ).joinToString(", ")

    /** Ligne secondaire affichée sous le nom : « 245 ko · 3 mars ». */
    fun visibleDetail(item: FileItem): String = listOfNotNull(
        if (item.isDirectory) item.childCount?.let(::itemCount) else SizeFormatter.short(item.sizeBytes),
        DateFormatter.short(item.lastModified),
    ).joinToString(" · ")

    /** « vide », « 1 élément », « 12 éléments ». */
    fun itemCount(count: Int): String = when (count) {
        0 -> "vide"
        1 -> "1 élément"
        else -> "$count éléments"
    }
}
