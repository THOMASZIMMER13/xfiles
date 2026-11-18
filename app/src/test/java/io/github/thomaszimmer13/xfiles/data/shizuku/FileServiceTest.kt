package io.github.thomaszimmer13.xfiles.data.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** M-01 : le service aux droits « shell » n'agit que dans Android/data, obb et media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun denied(block: () -> Unit) = try {
        block()
        fail("chemin accepté")
    } catch (e: IllegalStateException) {
        assertEquals(FileService.DENIED, e.message)
    }

    @Test fun pathsOutsideAllowedFoldersAreDenied() {
        val android = File(tmp.root, "storage/emulated/0/Android").apply { File(this, "data/com.exemple").mkdirs(); File(this, "obb").mkdirs() }
        File(tmp.root, "storage/emulated/0/DCIM").mkdirs()
        val secret = File(tmp.root, "data/local/tmp/secret").apply { parentFile!!.mkdirs(); writeText("x") }
        val service = FileService(android.path)

        // Autorisé : la racine Android (lecture) et l'intérieur de data / obb / media.
        assertTrue(service.list(android.path).contains("data"))
        assertTrue(service.list("${android.path}/data").contains("com.exemple"))
        service.mkdir("${android.path}/data/com.exemple/sauvegardes")

        // Refusé : traversée, chemin absolu ailleurs, racines elles-mêmes en écriture.
        denied { service.list("${android.path}/data/../../DCIM") }
        denied { service.list(secret.parent) }
        denied { service.delete(secret.path) }
        denied { service.delete("${android.path}/data") }
        denied { service.delete(android.path) }
        denied { service.mkdir("${android.path}/autre") }
        denied { service.rename("${android.path}/data/com.exemple", "${android.path}/../../DCIM/vol") }
        denied { service.openWrite("${android.path}/../x.txt") }
        assertTrue(secret.exists())
    }
}
