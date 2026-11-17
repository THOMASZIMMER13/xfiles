package io.github.thomaszimmer13.xfiles.data.incoming

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ce qu'une autre app demande à XFiles. */
sealed interface IncomingRequest {
    /** Fichiers partagés vers XFiles : choisir un dossier, puis « Enregistrer ici ». */
    data class Save(val uris: List<Uri>, val names: List<String>) : IncomingRequest

    /** Une app demande un fichier (« Joindre un fichier ») : ouvrir un fichier le lui renvoie. */
    data class Pick(val mimeTypes: List<String>) : IncomingRequest {
        /** Le fichier de type [mime] convient-il ? « image/(étoile) » accepte toute image. */
        fun accepts(mime: String): Boolean = mimeTypes.isEmpty() || mimeTypes.any { wanted ->
            wanted == "*/*" || wanted.equals(mime, ignoreCase = true) ||
                (wanted.endsWith("/*") && mime.startsWith(wanted.dropLast(1), ignoreCase = true))
        }
    }

    /** Raccourci de l'écran d'accueil : ouvrir ce dossier. */
    data class OpenFolder(val path: String) : IncomingRequest
}

/** Demande en cours, posée par l'activité et lue par l'interface. */
class IncomingRequests {
    private val _current = MutableStateFlow<IncomingRequest?>(null)
    val current: StateFlow<IncomingRequest?> = _current.asStateFlow()

    fun set(request: IncomingRequest?) {
        _current.value = request
    }

    fun clear() {
        _current.value = null
    }
}
