package io.github.thomaszimmer13.xfiles.data.remote

import io.github.thomaszimmer13.xfiles.R
import java.security.SecureRandom

/** Services en ligne proposés (moteurs rclone inclus dans XFiles), tous connectés par OAuth. */
enum class CloudProvider(val type: String, val label: Int) {
    GOOGLE_DRIVE("drive", R.string.cloud_provider_drive),
    ONEDRIVE("onedrive", R.string.cloud_provider_onedrive),
    DROPBOX("dropbox", R.string.cloud_provider_dropbox),
    BOX("box", R.string.cloud_provider_box),
    PCLOUD("pcloud", R.string.cloud_provider_pcloud),
    YANDEX("yandex", R.string.cloud_provider_yandex),
    ;

    companion object {
        fun of(type: String): CloudProvider? = entries.firstOrNull { it.type == type }

        /** Nom de la configuration rclone d'un compte (lettres et chiffres seulement). */
        fun remoteName(serverId: String) = "xf" + serverId.filter { it.isLetterOrDigit() }.take(16)

        fun randomPassword(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
