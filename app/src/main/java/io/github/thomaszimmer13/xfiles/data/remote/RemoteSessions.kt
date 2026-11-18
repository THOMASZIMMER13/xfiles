package io.github.thomaszimmer13.xfiles.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.ConcurrentHashMap

/**
 * Connexions ouvertes, une par serveur, utilisées par une opération à la fois.
 * Une connexion tombée est rouverte une fois ; elle est fermée après trois minutes sans servir.
 */
class RemoteSessions(private val scope: CoroutineScope, private val rclone: Rclone? = null) {
    private class Slot(val mutex: Mutex = Mutex(), var client: RemoteClient? = null, var key: RemoteServer? = null, var idle: Job? = null)

    private val slots = ConcurrentHashMap<String, Slot>()

    init {
        // Android fournit une version réduite de Bouncy Castle sous le même nom : on la remplace
        // par la complète (chiffrements modernes des serveurs SSH).
        runCatching {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    suspend fun <T> use(server: RemoteServer, block: (RemoteClient) -> T): T {
        val slot = slots.getOrPut(server.id) { Slot() }
        return slot.mutex.withLock {
            slot.idle?.cancel()
            try {
                runInterruptible(Dispatchers.IO) {
                    val client = client(slot, server)
                    try {
                        block(client)
                    } catch (e: RemoteException) {
                        if (e.reason != RemoteException.Reason.UNREACHABLE) throw e
                        // Connexion coupée (veille, changement de réseau) : on réessaie une fois.
                        drop(slot)
                        block(client(slot, server))
                    }
                }
            } catch (e: Exception) {
                if (e is RemoteException && e.reason == RemoteException.Reason.UNREACHABLE) drop(slot)
                throw e
            } finally {
                slot.idle = scope.launch {
                    delay(IDLE_MS)
                    slot.mutex.withLock { drop(slot) }
                }
            }
        }
    }

    private fun client(slot: Slot, server: RemoteServer): RemoteClient {
        val current = slot.client
        if (current != null && same(slot.key, server) && current.isAlive) return current
        drop(slot)
        return RemoteClient.connect(server, rclone).also {
            slot.client = it
            slot.key = server
        }
    }

    private fun same(a: RemoteServer?, b: RemoteServer) = a == b

    private fun drop(slot: Slot) {
        runCatching { slot.client?.close() }
        slot.client = null
        slot.key = null
    }

    /** Serveur modifié ou supprimé : sa connexion est fermée. */
    fun forget(id: String) {
        slots.remove(id)?.let { slot -> scope.launch { slot.mutex.withLock { drop(slot) } } }
    }

    private companion object {
        const val IDLE_MS = 180_000L
    }
}
