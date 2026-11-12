package io.github.thomaszimmer13.xfiles.data.volumes

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.thomaszimmer13.xfiles.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.io.File

enum class LocationKind { INTERNAL, DOWNLOADS, DOCUMENTS, REMOVABLE }

/** Un emplacement affiché à la racine d'Explorer. */
data class StorageLocation(
    val kind: LocationKind,
    val name: String,
    val path: String,
    /** Espace libre et total, uniquement pour les volumes (pas pour les raccourcis de dossier). */
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
)

/** Liste les volumes de stockage montés et suit leurs branchements / retraits en direct. */
class VolumeRepository(private val context: Context) {

    private val storageManager = context.getSystemService(StorageManager::class.java)

    /** Instantané des emplacements disponibles, dans l'ordre d'affichage. */
    fun currentLocations(): List<StorageLocation> {
        val mounted = storageManager.storageVolumes.filter { it.isMounted() }
        val result = mutableListOf<StorageLocation>()

        mounted.firstOrNull { it.isPrimary }?.directory?.let { root ->
            result += volumeLocation(LocationKind.INTERNAL, context.getString(R.string.location_internal), root)
            folderShortcut(root, Environment.DIRECTORY_DOWNLOADS, LocationKind.DOWNLOADS, R.string.location_downloads)
                ?.let { result += it }
            folderShortcut(root, Environment.DIRECTORY_DOCUMENTS, LocationKind.DOCUMENTS, R.string.location_documents)
                ?.let { result += it }
        }

        mounted.filter { !it.isPrimary }.forEach { volume ->
            val dir = volume.directory ?: return@forEach
            // Nom fourni par Android dans la langue du téléphone, ex. « Carte SD SanDisk », « Clé USB ».
            result += volumeLocation(LocationKind.REMOVABLE, volume.getDescription(context), dir)
        }
        return result
    }

    /** Émet la liste à jour à chaque montage ou démontage de volume. */
    fun locations(): Flow<List<StorageLocation>> = callbackFlow {
        trySend(currentLocations())
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                trySend(currentLocations())
            }
        }
        storageManager.registerStorageVolumeCallback(context.mainExecutor, callback)
        awaitClose { storageManager.unregisterStorageVolumeCallback(callback) }
    }.conflate().flowOn(Dispatchers.IO)

    private fun StorageVolume.isMounted(): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun volumeLocation(kind: LocationKind, name: String, dir: File): StorageLocation {
        val (free, total) = try {
            val stat = StatFs(dir.path)
            stat.availableBytes to stat.totalBytes
        } catch (_: IllegalArgumentException) {
            null to null
        }
        return StorageLocation(kind, name, dir.path, free, total)
    }

    private fun folderShortcut(root: File, dirName: String, kind: LocationKind, label: Int): StorageLocation? {
        val dir = File(root, dirName)
        return if (dir.isDirectory) StorageLocation(kind, context.getString(label), dir.path) else null
    }
}
