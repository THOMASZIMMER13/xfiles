package io.github.thomaszimmer13.xfiles.a11y

import io.github.thomaszimmer13.xfiles.data.source.FileItem
import java.time.ZonedDateTime

/**
 * Texte d'une ligne de fichier ou de dossier.
 * TalkBack lit, dans cet ordre : nom, type, taille (ou nombre d'éléments), date.
 * Taille inconnue (négative) et date inconnue (0 ou moins) sont omises (audit M-05) :
 * jamais « 0 octet » ni « modifié le 1er janvier 1970 ».
 */
object FileDescriptions {

    fun spoken(item: FileItem, now: ZonedDateTime = ZonedDateTime.now()): String = listOfNotNull(
        item.name,
        FileTypeDescriber.describe(item.name, item.isDirectory),
        if (item.isDirectory) item.childCount?.let(::itemCount) else item.sizeBytes.takeIf { it >= 0 }?.let(SizeFormatter::spoken),
        item.lastModified.takeIf { it > 0 }?.let { DateFormatter.spoken(it, now) },
    ).joinToString(", ")

    /** Ligne secondaire affichée sous le nom : « 245 ko · 3 mars ». */
    fun visibleDetail(item: FileItem): String = listOfNotNull(
        if (item.isDirectory) item.childCount?.let(::itemCount) else item.sizeBytes.takeIf { it >= 0 }?.let(SizeFormatter::short),
        item.lastModified.takeIf { it > 0 }?.let { DateFormatter.short(it) },
    ).joinToString(" · ")

    /** « aucun élément sélectionné », « 1 élément sélectionné », « 3 éléments sélectionnés ». */
    fun selectedCount(count: Int): String = when (count) {
        0 -> "aucun élément sélectionné"
        1 -> "1 élément sélectionné"
        else -> "$count éléments sélectionnés"
    }

    /** « vide », « 1 élément », « 12 éléments ». */
    fun itemCount(count: Int): String = when (count) {
        0 -> "vide"
        1 -> "1 élément"
        else -> "$count éléments"
    }
}
