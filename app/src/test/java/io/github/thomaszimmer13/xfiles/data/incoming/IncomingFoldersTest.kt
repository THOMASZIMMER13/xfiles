package io.github.thomaszimmer13.xfiles.data.incoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit N-04 : un raccourci entrant n'ouvre qu'un dossier du stockage partagé, jamais une route. */
class IncomingFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlySharedFolders() {
        val storage = File(tmp.root, "storage/emulated/0").apply { mkdirs() }
        val music = File(storage, "Music").apply { mkdirs() }
        val own = File(storage, "Android/data/io.github.thomaszimmer13.xfiles").apply { mkdirs() }
        val data = File(tmp.root, "data/user/0/io.github.thomaszimmer13.xfiles").apply { File(this, "files").mkdirs(); File(this, "cache/remote/vault-1").mkdirs() }
        val roots = listOf(storage.path)
        val private = listOf(data, File(data, "cache"), own)
        fun safe(p: String) = IncomingFolders.safe(p, roots, private)

        assertEquals(music.canonicalPath, safe(music.path))
        assertEquals(storage.canonicalPath, safe(storage.path))
        // Routes brutes refusées.
        assertNull(safe("xfiles:editeur:${data.path}/files/servers.json"))
        assertNull(safe("xfiles:serveur:abc"))
        // Données privées, cache, coffres déchiffrés : refusés même s'ils étaient dans un volume.
        assertNull(safe("${data.path}/files"))
        assertNull(safe("${data.path}/cache/remote/vault-1"))
        assertNull(IncomingFolders.safe("${data.path}/cache", listOf(tmp.root.path), private))
        assertNull(safe(own.path))
        // Traversée, relatif, octet nul, fichier, absent.
        assertNull(safe("${storage.path}/../../../data/user/0"))
        assertNull(safe("Music"))
        assertNull(safe("${music.path}\u0000"))
        File(music, "a.mp3").writeText("x")
        assertNull(safe("${music.path}/a.mp3"))
        assertNull(safe("${storage.path}/Absent"))
        // Préfixe commun : « 0bis » n'est pas dans « 0 ».
        val bis = File(tmp.root, "storage/emulated/0bis").apply { mkdirs() }
        assertNull(safe(bis.path))
    }
}
