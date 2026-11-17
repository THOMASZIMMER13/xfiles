package io.github.thomaszimmer13.xfiles.data.document

/**
 * Repère les adresses web, courriels et numéros de téléphone dans un texte.
 * Les adresses postales sont repérées à part, par le système (voir le lecteur).
 */
object Linkifier {

    private val WEB = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'()\[\]]+[^\s<>"'()\[\].,;:!?])""")
    private val EMAIL = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")

    /** Numéros français (06 12 34 56 78, 01.23.45.67.89, +33 6 12 34 56 78) et internationaux (+44 20 7946 0958). */
    private val PHONE = Regex("""(?<![\w+])(?:\+\d{1,3}[\s.-]?(?:\(0\)[\s.-]?)?\d(?:[\s.-]?\d{1,4}){3,6}|0\d(?:[\s.-]?\d{2}){4})(?!\w)""")

    fun find(text: String): List<DocLink> {
        val links = mutableListOf<DocLink>()
        fun overlaps(start: Int, end: Int) = links.any { start < it.end && end > it.start }

        for (m in EMAIL.findAll(text)) links += DocLink(m.range.first, m.range.last + 1, "mailto:${m.value}", LinkKind.EMAIL)
        for (m in WEB.findAll(text)) {
            val r = m.groups[1]!!.range
            if (overlaps(r.first, r.last + 1)) continue
            val url = m.groups[1]!!.value
            links += DocLink(r.first, r.last + 1, if (url.startsWith("www.", true)) "https://$url" else url, LinkKind.WEB)
        }
        for (m in PHONE.findAll(text)) {
            val digits = m.value.count(Char::isDigit)
            if (digits < 10 || digits > 15 || overlaps(m.range.first, m.range.last + 1)) continue
            val number = m.value.replace(Regex("""\(0\)"""), "").filter { it.isDigit() || it == '+' }
            links += DocLink(m.range.first, m.range.last + 1, "tel:$number", LinkKind.PHONE)
        }
        return links.sortedBy { it.start }
    }

    /** Ajoute les liens trouvés dans le texte à ceux déjà connus (liens HTML…), sans chevauchement. */
    fun merge(existing: List<DocLink>, text: String): List<DocLink> {
        val found = find(text).filter { f -> existing.none { f.start < it.end && f.end > it.start } }
        return (existing + found).sortedBy { it.start }
    }
}
