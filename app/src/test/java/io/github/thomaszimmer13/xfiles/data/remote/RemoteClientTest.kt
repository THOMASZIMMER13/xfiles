package io.github.thomaszimmer13.xfiles.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Clients réseau contre de vrais serveurs locaux (OpenSSH, Samba, pyftpdlib, wsgidav).
 * Lancé seulement si XFILES_REMOTE_TEST=1 (serveurs démarrés à la main, voir le script de test).
 */
class RemoteClientTest {
    private val enabled = System.getenv("XFILES_REMOTE_TEST") == "1"
    private val password = "Mot2passe!"

    private fun sftpKey(): String = try {
        RemoteClient.connect(server(Protocol.SFTP)).close()
        fail("clé inconnue acceptée")
        ""
    } catch (e: HostKeyException) {
        assertFalse(e.changed)
        e.fingerprint
    }

    private fun server(p: Protocol, key: String = "") = when (p) {
        Protocol.SFTP -> RemoteServer("s", p, "ssh", "127.0.0.1", 2222, "", "xtest", password, hostKey = key)
        Protocol.SMB -> RemoteServer("m", p, "smb", "127.0.0.1", 4445, "Partage", "xtest", password)
        Protocol.FTP -> RemoteServer("f", p, "ftp", "127.0.0.1", 2121, "", "xtest", password)
        Protocol.WEBDAV -> RemoteServer("w", p, "dav", "127.0.0.1", 8080, "/dav", "xtest", password, secure = false)
        Protocol.FTPS -> error("non testé")
    }

    private fun scenario(server: RemoteServer) {
        RemoteClient.connect(server).use { c ->
            val dir = "/xf-test"
            runCatching { c.delete(dir, true) }
            c.mkdir(dir)
            c.mkdir("$dir/sous dossier")
            val data = "Bonjour, éèà ✓\n".repeat(5000).toByteArray()
            c.upload(data.inputStream(), data.size.toLong(), "$dir/Été 2026.txt")
            c.upload("x".toByteArray().inputStream(), 1, "$dir/sous dossier/b.txt")
            val list = c.list(dir).associateBy { it.name }
            assertEquals(setOf("Été 2026.txt", "sous dossier"), list.keys)
            assertEquals(data.size.toLong(), list.getValue("Été 2026.txt").size)
            assertTrue(list.getValue("sous dossier").isDirectory)
            assertEquals("$dir/Été 2026.txt", list.getValue("Été 2026.txt").path)
            val out = ByteArrayOutputStream()
            var last = 0L
            c.download("$dir/Été 2026.txt", out) { done, _ -> last = done }
            assertTrue(out.toByteArray().contentEquals(data))
            assertEquals(data.size.toLong(), last)
            c.rename("$dir/Été 2026.txt", "$dir/renommé.txt")
            assertEquals(setOf("renommé.txt", "sous dossier"), c.list(dir).map { it.name }.toSet())
            try {
                c.list("/n-existe-pas")
                fail("dossier absent listé")
            } catch (e: RemoteException) {
                assertEquals(RemoteException.Reason.NOT_FOUND, e.reason)
            }
            c.delete(dir, true)
            assertFalse(c.list("/").any { it.name == "xf-test" })
        }
        try {
            RemoteClient.connect(server.copy(password = "faux")).close()
            fail("mauvais mot de passe accepté")
        } catch (e: RemoteException) {
            assertEquals(RemoteException.Reason.AUTH, e.reason)
        }
    }

    @Test fun sftp() {
        assumeTrue(enabled)
        val key = sftpKey()
        assertTrue(key.startsWith("SHA256:"))
        scenario(server(Protocol.SFTP, key))
        try {
            RemoteClient.connect(server(Protocol.SFTP, "SHA256:autre")).close()
            fail("clé changée acceptée")
        } catch (e: HostKeyException) {
            assertTrue(e.changed)
        }
    }

    @Test fun smb() {
        assumeTrue(enabled)
        scenario(server(Protocol.SMB))
    }

    @Test fun ftp() {
        assumeTrue(enabled)
        scenario(server(Protocol.FTP))
    }

    @Test fun webdav() {
        assumeTrue(enabled)
        scenario(server(Protocol.WEBDAV))
    }
}
