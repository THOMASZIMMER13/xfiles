package io.github.thomaszimmer13.xfiles.data.incoming

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** H-02 : partages entrants refusés vers les fichiers privés, et FileProvider restreint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingUrisTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val pkg = app.packageName

    @Test fun ownProviderAndFileUrisAreRefused() {
        assertFalse(IncomingUris.acceptable(Uri.parse("content://$pkg.fileprovider/root/data/user/0/$pkg/cache/remote/vault-1/releve.pdf"), pkg))
        assertFalse(IncomingUris.acceptable(Uri.parse("content://$pkg.shizuku/x"), pkg))
        assertFalse(IncomingUris.acceptable(Uri.parse("content://$pkg/x"), pkg))
        assertFalse(IncomingUris.acceptable(Uri.parse("file:///data/user/0/$pkg/shared_prefs/securite.xml"), pkg))
        assertFalse(IncomingUris.acceptable(Uri.parse("file:///storage/emulated/0/a.jpg"), pkg))
        assertTrue(IncomingUris.acceptable(Uri.parse("content://com.android.providers.media.documents/document/image%3A12"), pkg))
        assertTrue(IncomingUris.acceptable(Uri.parse("content://${pkg}x.autre/1"), pkg)) // autre app au nom proche
    }

    private val authority get() = "$pkg.fileprovider"

    /**
     * Un seul test pour FileProvider : il garde sa configuration en cache statique, alors que
     * Robolectric change de dossier de données à chaque test.
     */
    @Test fun providerSharesPublicAndRemoteFilesButNeverPrivateOnes() {
        listOf(
            File("/storage/emulated/0/Download/a.pdf"),
            File("/storage/1A2B-3C4D/DCIM/b.jpg"), // carte SD / clé USB
            File(app.cacheDir, "remote/srv/Photos/c.jpg"), // serveur, compte, coffre, Shizuku
            File(app.cacheDir, "apercu/d.txt"), // aperçu d'archive
        ).forEach { assertNotNull(it.path, FileProvider.getUriForFile(app, authority, it)) }

        listOf(
            File(app.filesDir, "servers.json"),
            File(app.filesDir, "rclone/rclone.conf"),
            File(app.dataDir, "shared_prefs/securite.xml"),
            File(app.cacheDir, "rclone-transfers/xfdown1"),
        ).forEach {
            try {
                FileProvider.getUriForFile(app, authority, it)
                fail("${it.path} partageable")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
