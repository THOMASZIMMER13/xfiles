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
}
