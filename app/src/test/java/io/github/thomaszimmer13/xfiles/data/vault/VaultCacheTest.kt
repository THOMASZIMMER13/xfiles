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
import kotlinx.coroutines.flow.first

/** H-04 : aucune copie en clair d'un coffre ne survit à un arrêt brutal de l'application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class) // sans le nettoyage au démarrage d'XFiles, qui ferait la course avec le test
class VaultCacheTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun vaults(keys: VaultKeys, onLocked: () -> Unit = {}): Vaults {
        val store = ServerStore(app)
        val rclone = Rclone(app) // pas d'appel : le pont Go n'est pas chargé
        return Vaults(app, rclone, store, RemoteSessions(CoroutineScope(Dispatchers.Unconfined), rclone, keys::get), keys, onLocked)
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

    /** Audit N-05 : fermer les coffres efface aussi le texte OCR et les aperçus, et oublie les Récents. */
    @Test fun lockAllPurgesOcrPreviewsAndRecents() = kotlinx.coroutines.runBlocking {
        val prefs = io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository(app)
        val copy = File(app.cacheDir, "remote/vault-b/Banque/releve.pdf").apply { parentFile!!.mkdirs(); writeText("en clair") }
        val normal = File(app.filesDir, "Documents/note.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        // Une copie de coffre n'entre jamais dans les Récents ni les positions de lecture.
        prefs.recordOpened(copy.path)
        prefs.recordOpened(normal.path)
        prefs.setDocPosition(copy.path, 12)
        prefs.setResumePosition(copy.path, 5_000)
        assertEquals(listOf(normal.path), prefs.recentFiles.first().map { it.path })
        assertFalse(copy.path in prefs.docPositions.first())
        assertFalse(copy.path in prefs.resumePositions.first())

        // Texte OCR d'une copie de coffre : rangé à part ; celui d'un fichier ordinaire reste.
        val ocr = io.github.thomaszimmer13.xfiles.data.ocr.OcrService(app, CoroutineScope(Dispatchers.Unconfined))
        ocr.store(copy, listOf(listOf("Solde : 1 234 euros")))
        ocr.store(normal, listOf(listOf("Note")))
        assertTrue(File(app.cacheDir, "ocr/ephemere").list()!!.isNotEmpty())
        val preview = File(app.cacheDir, "apercu/contrat.pdf").apply { parentFile!!.mkdirs(); writeText("extrait d'un zip chiffré") }

        val keys = VaultKeys().apply { put("vault-b", "k") }
        var locked = 0
        vaults(keys) { locked++ }.lockAll()
        assertFalse(copy.exists())
        assertFalse(File(app.cacheDir, "ocr/ephemere").exists())
        assertFalse(preview.exists())
        assertEquals(null, ocr.cached(copy))
        assertEquals(listOf(listOf("Note")), ocr.cached(normal))
        assertTrue(locked > 0)
    }
}
