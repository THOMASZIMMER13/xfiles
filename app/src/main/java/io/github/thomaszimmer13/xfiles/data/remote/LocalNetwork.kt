package io.github.thomaszimmer13.xfiles.data.remote

/**
 * Serveurs du réseau local, seuls autorisés en clair (HTTP WebDAV, audit N-06). Décidé sur l'adresse
 * saisie, sans résolution DNS (une résolution pourrait être détournée et bloquerait l'interface) :
 * adresses privées (RFC 1918, CGNAT de Tailscale, lien local, bouclage, IPv6 locale) et noms locaux
 * (« nas.local », « box.lan », « home.arpa », nom sans point).
 */
object LocalNetwork {
    private val localSuffixes = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localdomain", ".intranet")

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        ipv4(host)?.let { return privateV4(it) }
        if (':' in host) return privateV6(host)
        if (host == "localhost") return true
        if ('.' !in host) return true // nom NetBIOS / mDNS court : « nas », « diskstation »
        return localSuffixes.any { host.endsWith(it) }
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.length <= 3 && it in 0..255 } ?: return null }
        return bytes.toIntArray()
    }

    private fun privateV4(b: IntArray): Boolean = when {
        b[0] == 10 -> true
        b[0] == 172 && b[1] in 16..31 -> true
        b[0] == 192 && b[1] == 168 -> true
        b[0] == 127 -> true
        b[0] == 169 && b[1] == 254 -> true
        b[0] == 100 && b[1] in 64..127 -> true // CGNAT (Tailscale, opérateurs)
        else -> false
    }

    private fun privateV6(host: String): Boolean {
        val h = host.substringBefore('%')
        if (h == "::1") return true
        val first = h.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80 // ULA fc00::/7, lien local fe80::/10
    }
}
