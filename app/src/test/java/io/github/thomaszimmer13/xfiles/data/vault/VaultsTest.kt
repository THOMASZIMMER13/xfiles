package io.github.thomaszimmer13.xfiles.data.vault

import io.github.thomaszimmer13.xfiles.data.remote.Protocol
import io.github.thomaszimmer13.xfiles.data.remote.RemoteServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nom rclone d'un coffre ouvert (le chiffrement lui-même est vérifié par le test Go). */
class VaultsTest {
    @Test fun remoteQuotesLocationAndKey() {
        val server = RemoteServer("vault-1", Protocol.VAULT, "Perso", "/storage/emulated/0/Documents/Coffres XFiles/Coffre d'été, 1")
        assertEquals(
            ":crypt,remote='/storage/emulated/0/Documents/Coffres XFiles/Coffre d''été, 1',password='ab''c'",
            Vaults.remoteOf(server, "ab'c"),
        )
    }

    @Test fun cloudLocationKeepsAccountPrefix() {
        val server = RemoteServer("vault-2", Protocol.VAULT, "Banque", "xf1a2b:Coffres XFiles/Banque")
        assertEquals(":crypt,remote='xf1a2b:Coffres XFiles/Banque',password='k'", Vaults.remoteOf(server, "k"))
    }

    /** Audit F-07 : mauvais mot de passe d'un côté, panne de l'autre. */
    @Test fun wrongPasswordIsTellableFromFailures() {
        fun e(reason: io.github.thomaszimmer13.xfiles.data.remote.RemoteException.Reason, m: String = "") =
            io.github.thomaszimmer13.xfiles.data.remote.RemoteException(reason, m)
        val r = io.github.thomaszimmer13.xfiles.data.remote.RemoteException.Reason.entries.associateBy { it.name }
        org.junit.Assert.assertTrue(Vaults.wrongPassword(e(r.getValue("NOT_FOUND"))))
        org.junit.Assert.assertTrue(Vaults.wrongPassword(e(r.getValue("PROTOCOL"), "/.xfiles-coffre : failed to authenticate decrypted block - bad password?")))
        org.junit.Assert.assertFalse(Vaults.wrongPassword(e(r.getValue("PROTOCOL"), "/.xfiles-coffre : unexpected EOF")))
        org.junit.Assert.assertFalse(Vaults.wrongPassword(e(r.getValue("UNREACHABLE"))))
        org.junit.Assert.assertFalse(Vaults.wrongPassword(e(r.getValue("AUTH"))))
    }
}
