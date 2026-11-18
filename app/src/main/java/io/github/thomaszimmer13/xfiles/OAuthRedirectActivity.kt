package io.github.thomaszimmer13.xfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Retour de la page de connexion Dropbox ou OneDrive (« xfiles-auth://dropbox?code=… ») :
 * transmet l'adresse puis revient sur XFiles en fermant l'onglet du navigateur.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as XFilesApplication).container.cloud.onRedirect(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
