package io.github.thomaszimmer13.xfiles.data.document

/** Un lien dans un paragraphe : [start] et [end] sont des positions dans le texte. */
data class DocLink(val start: Int, val end: Int, val target: String, val kind: LinkKind)

enum class LinkKind { WEB, EMAIL, PHONE, ADDRESS, INTERNAL }

/**
 * Un bloc du document, lu comme un seul élément par TalkBack.
 * Les titres sont des en-têtes : on passe de l'un à l'autre avec la navigation par titres.
 */
sealed interface DocBlock {
    val text: String

    data class Heading(val level: Int, override val text: String, val links: List<DocLink> = emptyList()) : DocBlock

    data class Paragraph(override val text: String, val links: List<DocLink> = emptyList()) : DocBlock

    /** Élément de liste ; [marker] : « • », « 1. »… */
    data class ListItem(override val text: String, val level: Int = 0, val marker: String = "•", val links: List<DocLink> = emptyList()) : DocBlock

    /** Ligne d'un tableau, lue « cellule 1, cellule 2… ». */
    data class TableRow(val cells: List<String>, val header: Boolean = false) : DocBlock {
        override val text: String get() = cells.joinToString(" ; ")
    }

    /** Début de page (PDF) ou de chapitre sans titre. */
    data class PageStart(val number: Int) : DocBlock {
        override val text: String get() = ""
    }

    /** Bloc de code ou texte préformaté. */
    data class Preformatted(override val text: String) : DocBlock
}

/** Document prêt à lire. [pages] > 0 pour un PDF. */
data class Document(
    val title: String,
    val blocks: List<DocBlock>,
    val pages: Int = 0,
    /** Le texte brut peut être modifié dans l'éditeur (txt, md, csv…). */
    val editable: Boolean = false,
    /** Lu partiellement (fichier trop gros). */
    val truncated: Boolean = false,
)

/** Le fichier n'a pas pu être lu comme document. */
class DocumentException(message: String) : Exception(message)
