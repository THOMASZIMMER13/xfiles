package io.github.thomaszimmer13.xfiles.domain

/** Un élément du fil d'Ariane : nom affiché et chemin. */
data class Crumb(val name: String, val path: String)

object Breadcrumbs {

    /**
     * « Mémoire interne › Documents › Factures » à partir de « /storage/emulated/0/Documents/Factures ».
     * @param volumeRoots couples (nom du volume, chemin racine).
     */
    fun of(path: String, volumeRoots: List<Pair<String, String>>): List<Crumb> {
        val volume = volumeRoots
            .filter { (_, root) -> path == root || path.startsWith("$root/") }
            .maxByOrNull { (_, root) -> root.length }

        val crumbs = mutableListOf<Crumb>()
        var base = ""
        if (volume != null) {
            crumbs += Crumb(volume.first, volume.second)
            base = volume.second
        }
        path.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { segment ->
            base = "$base/$segment"
            crumbs += Crumb(segment, base)
        }
        return crumbs
    }
}
