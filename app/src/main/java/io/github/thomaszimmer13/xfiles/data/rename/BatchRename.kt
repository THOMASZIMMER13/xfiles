package io.github.thomaszimmer13.xfiles.data.rename

import io.github.thomaszimmer13.xfiles.domain.NameValidator
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RenameMode { REPLACE, TEMPLATE }

enum class CaseChange { NONE, LOWER, UPPER, WORDS, SENTENCE }

/** Règles de renommage par lot. Le modèle accepte {nom}, {n}, {date} et {ext}. */
data class RenameRules(
    val mode: RenameMode = RenameMode.REPLACE,
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val ignoreCase: Boolean = true,
    val template: String = "{nom}",
    val start: Int = 1,
    /** 0 : autant de chiffres que le plus grand numéro (« 01 » à « 12 »). */
    val digits: Int = 0,
    val case: CaseChange = CaseChange.NONE,
    /** Faux : l'extension (« .jpg ») est gardée telle quelle. */
    val includeExtension: Boolean = false,
)

enum class RenameStatus { RENAMED, UNCHANGED, DUPLICATE, EXISTS, INVALID, BAD_PATTERN }

data class RenameSource(val path: String, val name: String, val lastModified: Long, val isDirectory: Boolean)

data class RenamePlanItem(val source: RenameSource, val newName: String, val status: RenameStatus)

/** Ce qui a été fait, pour pouvoir l'annuler : ancien chemin → nouveau chemin. */
data class RenameDone(val oldPath: String, val newPath: String)

object BatchRename {

    fun plan(sources: List<RenameSource>, others: Collection<String>, rules: RenameRules): List<RenamePlanItem> {
        val pattern = if (rules.mode == RenameMode.REPLACE && rules.regex && rules.find.isNotEmpty()) {
            runCatching { Regex(rules.find, if (rules.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) }.getOrNull()
                ?: return sources.map { RenamePlanItem(it, it.name, RenameStatus.BAD_PATTERN) }
        } else {
            null
        }
        val digits = if (rules.digits > 0) rules.digits else (rules.start + sources.size - 1).toString().length
        val proposed = sources.mapIndexed { index, s ->
            val raw = try {
                transform(s, index, rules, pattern, digits)
            } catch (_: Exception) {
                return sources.map { RenamePlanItem(it, it.name, RenameStatus.BAD_PATTERN) }
            }
            s to raw.trim()
        }
        // Les noms des éléments renommés se libèrent ; les autres éléments du dossier restent pris.
        val renamedAway = sources.map { it.name.lowercase(Locale.ROOT) }.toSet()
        val taken = others.map { it.lowercase(Locale.ROOT) }.filter { it !in renamedAway }.toSet()
        val counts = proposed.groupingBy { it.second.lowercase(Locale.ROOT) }.eachCount()
        return proposed.map { (s, name) ->
            val key = name.lowercase(Locale.ROOT)
            val status = when {
                name == s.name -> RenameStatus.UNCHANGED
                NameValidator.check(name, emptyList()) !is io.github.thomaszimmer13.xfiles.domain.NameCheck.Valid -> RenameStatus.INVALID
                (counts[key] ?: 0) > 1 -> RenameStatus.DUPLICATE
                key in taken -> RenameStatus.EXISTS
                else -> RenameStatus.RENAMED
            }
            RenamePlanItem(s, name, status)
        }
    }

    private fun transform(s: RenameSource, index: Int, rules: RenameRules, pattern: Regex?, digits: Int): String {
        val dot = s.name.lastIndexOf('.')
        val hasExtension = !s.isDirectory && dot > 0
        val keepExtension = hasExtension && !rules.includeExtension
        val base = if (keepExtension) s.name.substring(0, dot) else s.name
        val extension = if (hasExtension) s.name.substring(dot + 1) else ""
        val changed = when (rules.mode) {
            RenameMode.REPLACE -> when {
                rules.find.isEmpty() -> base
                pattern != null -> pattern.replace(base, rules.replace)
                else -> base.replace(rules.find, rules.replace, ignoreCase = rules.ignoreCase)
            }
            RenameMode.TEMPLATE -> {
                val number = (rules.start + index).toString().padStart(digits, '0')
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(s.lastModified))
                rules.template
                    .replace("{nom}", base, ignoreCase = true)
                    .replace("{n}", number, ignoreCase = true)
                    .replace("{date}", date, ignoreCase = true)
                    .replace("{ext}", extension, ignoreCase = true)
            }
        }
        val cased = when (rules.case) {
            CaseChange.NONE -> changed
            CaseChange.LOWER -> changed.lowercase(Locale.FRENCH)
            CaseChange.UPPER -> changed.uppercase(Locale.FRENCH)
            CaseChange.SENTENCE -> changed.lowercase(Locale.FRENCH).replaceFirstChar { it.titlecase(Locale.FRENCH) }
            CaseChange.WORDS -> changed.lowercase(Locale.FRENCH).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.FRENCH) } }
        }
        return if (keepExtension) "$cased.$extension" else cased
    }

    /**
     * Renomme en deux temps (vers des noms provisoires, puis vers les noms finaux) : les échanges
     * (a → b et b → a) et les changements de casse seule marchent. En cas d'échec, tout est remis.
     */
    fun apply(items: List<RenamePlanItem>): List<RenameDone> = move(items.filter { it.status == RenameStatus.RENAMED }.map { it.source.path to it.newName })

    /** Annule : chaque élément reprend son ancien nom. */
    fun undo(done: List<RenameDone>): List<RenameDone> = move(done.map { it.newPath to File(it.oldPath).name })

    private fun move(moves: List<Pair<String, String>>): List<RenameDone> {
        val stamp = System.nanoTime()
        val temps = ArrayList<Pair<File, File>>() // original → provisoire
        try {
            moves.forEachIndexed { i, (path, _) ->
                val from = File(path)
                val tmp = File(from.parentFile, ".xfiles-renommage-$stamp-$i")
                if (!from.renameTo(tmp)) throw IOException(from.name)
                temps += from to tmp
            }
        } catch (e: IOException) {
            temps.asReversed().forEach { (from, tmp) -> tmp.renameTo(from) }
            throw e
        }
        val done = ArrayList<RenameDone>()
        try {
            moves.forEachIndexed { i, (path, name) ->
                val (from, tmp) = temps[i]
                val to = File(from.parentFile, name)
                if (to.exists() || !tmp.renameTo(to)) throw IOException(name)
                done += RenameDone(path, to.path)
            }
        } catch (e: IOException) {
            // Retour arrière : les éléments déjà renommés reprennent leur nom, les autres sortent du provisoire.
            done.forEach { File(it.newPath).renameTo(File(it.oldPath)) }
            temps.drop(done.size).forEach { (from, tmp) -> tmp.renameTo(from) }
            throw e
        }
        return done
    }
}
