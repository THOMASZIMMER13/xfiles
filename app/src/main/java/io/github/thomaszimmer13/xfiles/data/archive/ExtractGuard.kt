package io.github.thomaszimmer13.xfiles.data.archive

import io.github.thomaszimmer13.xfiles.data.source.FileOperationException
import io.github.thomaszimmer13.xfiles.data.source.FileOperationException.Reason
import io.github.thomaszimmer13.xfiles.data.transfer.TransferListener
import java.io.File

/**
 * Garde d'une extraction (audit F-02) : arrête tout si l'on écrit nettement plus que ce que l'archive
 * annonçait (bombe de décompression aux en-têtes mensongers) ou si l'espace libre du volume tombe
 * sous une réserve. L'extraction en cours est alors retirée (voir Archives.extract).
 */
class ExtractGuard(
    private val target: File,
    private val inner: TransferListener = TransferListener.NONE,
    private val maxBytes: Long = Long.MAX_VALUE,
    private val freeSpace: (File) -> Long = { it.usableSpace },
) : TransferListener {
    var written = 0L
        private set
    private var sinceCheck = 0L

    override fun onBytes(count: Long) {
        written += count
        sinceCheck += count
        inner.onBytes(count)
        if (written > maxBytes) throw FileOperationException(Reason.TOO_BIG, target.path)
        if (sinceCheck >= CHECK_EVERY) {
            sinceCheck = 0
            if (freeSpace(target) < RESERVE) throw FileOperationException(Reason.NO_SPACE, target.path)
        }
    }

    override val isCancelled: Boolean get() = inner.isCancelled

    /** Décision prise avant d'extraire, d'après la taille annoncée [declared] (0 : inconnue). */
    data class Plan(val noSpace: Boolean, val suspicious: Boolean, val maxBytes: Long)

    companion object {
        /** Espace laissé libre sur le volume, quoi qu'il arrive. */
        const val RESERVE = 64L * 1024 * 1024
        private const val CHECK_EVERY = 8L * 1024 * 1024

        /** Au-delà de cette taille et de ce taux de compression, on demande confirmation. */
        const val SUSPICIOUS_SIZE = 1L shl 30
        const val SUSPICIOUS_RATIO = 100

        fun plan(declared: Long, archiveSize: Long, free: Long): Plan {
            if (declared <= 0) return Plan(noSpace = false, suspicious = false, maxBytes = Long.MAX_VALUE)
            val suspicious = declared > SUSPICIOUS_SIZE && archiveSize > 0 && declared / archiveSize >= SUSPICIOUS_RATIO
            // Marge pour les tailles approximatives (métadonnées, dossiers), pas pour des gigaoctets cachés.
            val max = declared + declared / 20 + 16L * 1024 * 1024
            return Plan(noSpace = declared + RESERVE > free, suspicious = suspicious, maxBytes = max)
        }
    }
}
