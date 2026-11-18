package io.github.thomaszimmer13.xfiles

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Lien « Revenir à XFiles » de la page affichée après la connexion d'un compte en ligne
 * (« xfiles-auth://retour ») : ramène XFiles au premier plan en fermant l'onglet du navigateur.
 */
class ReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
