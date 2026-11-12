package io.github.thomaszimmer13.xfiles.domain

import java.util.Locale

/** Résultat de la vérification d'un nom de fichier ou de dossier. */
sealed interface NameCheck {
    /** Nom utilisable (espaces de début et de fin retirés). */
    data class Valid(val name: String) : NameCheck

    /** Renommage sans changement : rien à faire. */
    data object Unchanged : NameCheck

    data class Invalid(val problem: NameProblem) : NameCheck
}

enum class NameProblem { EMPTY, RESERVED, FORBIDDEN_CHARACTERS, TOO_LONG, ALREADY_EXISTS }

object NameValidator {

    /**
     * Caractères refusés partout : « / » est interdit par Android, les autres le sont sur les
     * cartes SD et clés USB (FAT, exFAT) et posent problème au partage vers Windows.
     */
    val FORBIDDEN: List<Char> = listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Limite des systèmes de fichiers courants : 255 octets en UTF-8. */
    private const val MAX_BYTES = 255

    /**
     * @param existing noms déjà présents dans le dossier.
     * @param current nom actuel de l'élément en cas de renommage (null pour une création).
     */
    fun check(input: String, existing: Collection<String>, current: String? = null): NameCheck {
        val name = input.trim()
        if (current != null && name == current) return NameCheck.Unchanged
        val problem = when {
            name.isEmpty() -> NameProblem.EMPTY
            name == "." || name == ".." -> NameProblem.RESERVED
            name.any { it in FORBIDDEN || it.code < 32 } -> NameProblem.FORBIDDEN_CHARACTERS
            name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> NameProblem.TOO_LONG
            // Comparaison sans tenir compte de la casse : le stockage partagé d'Android et les
            // cartes SD ne distinguent pas « Photos » de « photos ». Renommer « photos » en
            // « Photos » reste permis.
            existing.any { it.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) && !it.equals(current, ignoreCase = true) } ->
                NameProblem.ALREADY_EXISTS
            else -> null
        }
        return if (problem == null) NameCheck.Valid(name) else NameCheck.Invalid(problem)
    }

    /** « Nouveau dossier », ou « Nouveau dossier 2 », « 3 »… s'il existe déjà. */
    fun uniqueName(base: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in taken) return base
        var n = 2
        while ("$base $n".lowercase(Locale.ROOT) in taken) n++
        return "$base $n"
    }
}
