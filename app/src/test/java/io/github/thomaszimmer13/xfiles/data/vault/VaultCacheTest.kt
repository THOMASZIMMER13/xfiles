package io.github.thomaszimmer13.xfiles.data.vault

import androidx.test.core.app.ApplicationProvider
import io.github.thomaszimmer13.xfiles.data.remote.Rclone
import io.github.thomaszimmer13.xfiles.data.remote.RemoteSessions
import io.github.thomaszimmer13.xfiles.data.remote.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** H-04 : aucune copie en clair d'un coffre ne survit à un arrêt brutal de l'application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class) // sans le nettoyage au démarrage d'XFiles, qui ferait la course avec le test
class VaultCacheTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun vaults(keys: VaultKeys): Vaults {
        val store = ServerStore(app)
        val rclone = Rclone(app) // pas d'appel : le pont Go n'est pas chargé
        return Vaults(app, rclone, store, RemoteSessions(CoroutineScope(Dispatchers.Unconfined), rclone, keys::get), keys)
    }

    @Test fun startupPurgeRemovesTransferLeftoversAndClosedVaultCopies() {
        val transfers = File(app.cacheDir, "rclone-transfers").apply { mkdirs() }
        val leftover = File(transfers, "xfdown123.tmp").apply { writeText("relevé en clair") }
        val closed = File(app.cacheDir, "remote/vault-ferme/releve.pdf").apply { parentFile!!.mkdirs(); writeText("en clair") }
        val open = File(app.cacheDir, "remote/vault-ouvert/note.txt").apply { parentFile!!.mkdirs(); writeText("ouvert") }
        val server = File(app.cacheDir, "remote/srv1/photo.jpg").apply { parentFile!!.mkdirs(); writeText("serveur") }
        val keys = VaultKeys().apply { put("vault-ouvert", "k") }

        vaults(keys).purgeCache()
        assertFalse(leftover.exists())
        assertFalse(closed.exists())
        assertTrue(open.exists()) // coffre encore ouvert dans ce processus
        assertTrue(server.exists()) // cache d'un serveur ordinaire : non concerné
    }

    @Test fun lockAllKeepsTransfersInProgress() {
        val transfers = File(app.cacheDir, "rclone-transfers").apply { mkdirs() }
        val running = File(transfers, "xfup9.tmp").apply { writeText("envoi en cours") }
        val keys = VaultKeys().apply { put("vault-a", "k") }
        File(app.cacheDir, "remote/vault-a/x.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        vaults(keys).lockAll()
        assertTrue(running.exists()) // un transfert peut tourner pendant le verrouillage
        assertFalse(File(app.cacheDir, "remote/vault-a").exists())
        assertEquals(emptyMap<String, String>(), keys.open.value)
    }
}
