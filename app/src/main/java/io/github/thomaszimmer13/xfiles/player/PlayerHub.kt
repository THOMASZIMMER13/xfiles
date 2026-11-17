package io.github.thomaszimmer13.xfiles.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.thomaszimmer13.xfiles.R
import io.github.thomaszimmer13.xfiles.a11y.TimeFormatter
import io.github.thomaszimmer13.xfiles.data.feedback.Feedback
import io.github.thomaszimmer13.xfiles.data.media.MediaKinds
import io.github.thomaszimmer13.xfiles.data.media.ParsedPlaylist
import io.github.thomaszimmer13.xfiles.data.media.PlaylistEntry
import io.github.thomaszimmer13.xfiles.data.media.Playlists
import io.github.thomaszimmer13.xfiles.data.prefs.PreferencesRepository
import io.github.thomaszimmer13.xfiles.data.source.fileItemOf
import io.github.thomaszimmer13.xfiles.domain.FileSorter
import io.github.thomaszimmer13.xfiles.domain.SortOptions
import io.github.thomaszimmer13.xfiles.ui.components.Announcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Un élément de la file de lecture. */
data class QueueItem(val id: String, val title: String, val durationMs: Long)

/** Une piste audio ou de sous-titres que l'on peut choisir. */
data class TrackOption(val group: Int, val track: Int, val label: String, val selected: Boolean)

enum class RepeatChoice { OFF, ALL, ONE }

/** État du lecteur, observé par l'écran du lecteur et le mini-lecteur. */
data class PlayerUi(
    val active: Boolean = false,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = C.TIME_UNSET,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val repeat: RepeatChoice = RepeatChoice.OFF,
    val shuffle: Boolean = false,
    val isVideo: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    /** Fin du minuteur de sommeil (heure système) ; null s'il n'y en a pas. */
    val sleepAt: Long? = null,
    val sleepAtEndOfTrack: Boolean = false,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val subtitlesOff: Boolean = true,
    /** La lecture vient de reprendre à cette position : bouton « Depuis le début ». */
    val resumedFromMs: Long? = null,
) {
    val hasDuration: Boolean get() = durationMs != C.TIME_UNSET && durationMs > 0
}

/**
 * Pilote du lecteur pour toute l'app : se connecte au [PlaybackService], expose [state],
 * prépare les files (dossier, liste m3u), mémorise les positions de reprise, gère le minuteur
 * et fait les annonces : changement de piste, fin de liste, casque débranché, erreurs.
 * Le temps qui défile n'est jamais annoncé de lui-même.
 */
class PlayerHub(
    private val app: Application,
    private val preferences: PreferencesRepository,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    /** L'écran du lecteur est ouvert (au-dessus des onglets). */
    private val _screenOpen = MutableStateFlow(false)
    val screenOpen: StateFlow<Boolean> = _screenOpen.asStateFlow()

    /** Image dans l'image : seule la vidéo est affichée. */
    private val _pictureInPicture = MutableStateFlow(false)
    val pictureInPicture: StateFlow<Boolean> = _pictureInPicture.asStateFlow()

    val announcer = Announcer(scope)

    var settings = PreferencesRepository.PlayerSettings()
        private set
    private var sortOptions = SortOptions()
    private var positions: Map<String, Long> = emptyMap()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var lastCue = ""

    init {
        scope.launch { preferences.playerSettings.collect { settings = it } }
        scope.launch { preferences.sortOptions.collect { sortOptions = it } }
        scope.launch { preferences.resumePositions.collect { positions = it } }
    }

    /** Le contrôleur Media3, connecté au service à la première demande. */
    val player: Player? get() = controller

    private suspend fun connect(): MediaController {
        controller?.let { return it }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val connected = suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(app))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
        connected.addListener(listener)
        controller = connected
        startTicker()
        refresh()
        return connected
    }

    // --- Ouvrir ---

    /** Faut-il ouvrir ce fichier dans le lecteur intégré ? */
    fun handles(name: String): Boolean = settings.builtin && (MediaKinds.isMedia(name) || MediaKinds.isPlaylist(name))

    /**
     * Lit [path] et met en file les fichiers du même genre (audio ou vidéo) du dossier,
     * dans l'ordre de tri choisi par l'utilisateur.
     */
    fun openFile(path: String) {
        scope.launch {
            val file = File(path)
            val video = MediaKinds.isVideo(file.name)
            val siblings = withContext(Dispatchers.IO) {
                val items = file.parentFile?.listFiles().orEmpty()
                    .filter { it.isFile && (if (video) MediaKinds.isVideo(it.name) else MediaKinds.isAudio(it.name)) }
                    .map { fileItemOf(it) }
                FileSorter.sort(items, sortOptions.copy(showHidden = true)).map { it.path }
            }.ifEmpty { listOf(path) }
            val start = siblings.indexOf(path).coerceAtLeast(0)
            play(siblings.map { PlaylistEntry(it) }, start)
        }
    }

    /** Lit un fichier m3u, m3u8 ou pls. */
    fun openPlaylist(path: String) {
        scope.launch {
            val file = File(path)
            when (val parsed = withContext(Dispatchers.IO) { runCatching { Playlists.parse(file) }.getOrNull() }) {
                null -> fail(app.getString(R.string.player_playlist_unreadable, file.name))
                ParsedPlaylist.Stream -> play(listOf(PlaylistEntry(file.path, file.nameWithoutExtension)), 0, hls = true)
                is ParsedPlaylist.Entries -> {
                    if (parsed.entries.isEmpty()) {
                        fail(app.getString(R.string.player_playlist_empty, file.name))
                        return@launch
                    }
                    val res = app.resources
                    val message = buildString {
                        append(res.getQuantityString(R.plurals.player_playlist_loaded, parsed.entries.size, file.nameWithoutExtension, parsed.entries.size))
                        if (parsed.missing > 0) append(" ").append(res.getQuantityString(R.plurals.player_playlist_missing, parsed.missing, parsed.missing))
                    }
                    play(parsed.entries, 0, intro = message)
                }
            }
        }
    }

    /** Ajoute des fichiers à la fin de la file en cours (ou démarre une file). */
    fun enqueue(paths: List<String>) {
        scope.launch {
            val c = connect()
            if (c.mediaItemCount == 0) {
                play(paths.map { PlaylistEntry(it) }, 0)
                return@launch
            }
            c.addMediaItems(paths.map { mediaItemFor(PlaylistEntry(it)) })
            announcer.announce(app.resources.getQuantityString(R.plurals.player_enqueued, paths.size, paths.size))
            refresh()
        }
    }

    private suspend fun play(entries: List<PlaylistEntry>, start: Int, hls: Boolean = false, intro: String? = null) {
        val c = try {
            connect()
        } catch (_: Exception) {
            fail(app.getString(R.string.player_unavailable))
            return
        }
        val items = entries.map { mediaItemFor(it, hls) }
        val first = entries[start].location
        val resume = resumePositionFor(first)
        c.setMediaItems(items, start, resume ?: 0L)
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.prepare()
        c.play()
        _screenOpen.value = true
        _state.update { it.copy(resumedFromMs = resume) }
        refresh()
        val trackText = announcementForCurrent(c)
        val resumeText = resume?.let { app.getString(R.string.player_resumed_at, TimeFormatter.spoken(it)) }
        announcer.announce(listOfNotNull(intro, trackText, resumeText).joinToString(" "))
    }

    private fun mediaItemFor(entry: PlaylistEntry, hls: Boolean = false): MediaItem {
        val uri = if (entry.isRemote) entry.location.toUri() else Uri.fromFile(File(entry.location))
        val fallback = entry.title ?: if (entry.isRemote) entry.location else File(entry.location).nameWithoutExtension
        val builder = MediaItem.Builder()
            .setMediaId(entry.location)
            .setUri(uri)
            // Titre de secours : le titre lu dans le fichier (ID3…) le remplace s'il existe.
            .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(fallback).build())
        if (hls || entry.location.endsWith(".m3u8", true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (!entry.isRemote && MediaKinds.isVideo(entry.location)) {
            val video = File(entry.location)
            builder.setSubtitleConfigurations(
                MediaKinds.sidecarSubtitles(video).map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub))
                        .setMimeType(subtitleMime(sub.name))
                        .setLanguage(MediaKinds.subtitleLanguage(video, sub))
                        .setLabel(sub.name)
                        // Un fichier de sous-titres posé à côté de la vidéo est affiché d'office.
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
        }
        return builder.build()
    }

    private fun subtitleMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** Texte traduit, pour les annonces construites par l'interface. */
    fun announceText(id: Int, vararg args: Any): String = app.getString(id, *args)

    // --- Commandes ---

    fun openScreen() {
        if (_state.value.active) _screenOpen.value = true
    }

    fun closeScreen() {
        _screenOpen.value = false
    }

    fun setPictureInPicture(value: Boolean) {
        _pictureInPicture.value = value
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            savePosition()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        refresh()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0).let { if (c.duration > 0) it.coerceAtMost(c.duration) else it }
        c.seekTo(target)
        refresh()
    }

    fun skipBack() = seekBy(-settings.skipBackSeconds * 1000L)
    fun skipForward() = seekBy(settings.skipForwardSeconds * 1000L)

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else announcer.announce(app.getString(R.string.player_no_next))
    }

    /** Précédent : début de la piste si on y est depuis plus de 3 s, sinon piste précédente. */
    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) {
            c.seekTo(0)
            refresh()
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    fun restartFromBeginning() {
        controller?.seekTo(0)
        _state.update { it.copy(resumedFromMs = null) }
        announcer.announce(app.getString(R.string.player_restarted))
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        refresh()
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refresh()
    }

    fun setShuffle(value: Boolean) {
        controller?.shuffleModeEnabled = value
        refresh()
    }

    fun playAt(index: Int) {
        controller?.run {
            seekTo(index, resumePositionFor(getMediaItemAt(index).mediaId) ?: 0L)
            play()
        }
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (c.mediaItemCount <= 1) {
            stop()
            return
        }
        c.removeMediaItem(index)
        refresh()
    }

    /** Monte ou descend un élément de la file ; renvoie sa nouvelle position. */
    fun move(index: Int, up: Boolean): Int? {
        val c = controller ?: return null
        val target = if (up) index - 1 else index + 1
        if (target !in 0 until c.mediaItemCount) return null
        c.moveMediaItem(index, target)
        refresh()
        return target
    }

    fun stop() {
        savePosition()
        cancelSleep(announce = false)
        controller?.run {
            stop()
            clearMediaItems()
        }
        _screenOpen.value = false
        _state.value = PlayerUi()
    }

    /** File actuelle, pour l'enregistrer en liste m3u. */
    fun queueEntries(): List<PlaylistEntry> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val title = item.mediaMetadata.title?.toString() ?: item.mediaMetadata.displayTitle?.toString()
            PlaylistEntry(item.mediaId, title)
        }
    }

    // --- Pistes audio et sous-titres ---

    fun selectTrack(type: Int, option: TrackOption) {
        val c = controller ?: return
        val group = c.currentTracks.groups.getOrNull(option.group) ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.track))
            .build()
    }

    fun disableSubtitles() {
        val c = controller ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    // --- Minuteur de sommeil ---

    /** Arrête la lecture dans [minutes] minutes, avec un fondu de 10 secondes. */
    fun sleepIn(minutes: Int) {
        cancelSleep(announce = false)
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepAt = end, sleepAtEndOfTrack = false) }
        sleepJob = scope.launch {
            delay((end - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            fadeOutAndPause()
        }
        announcer.announce(app.resources.getQuantityString(R.plurals.player_sleep_set, minutes, minutes))
    }

    fun sleepAtEndOfTrack() {
        cancelSleep(announce = false)
        _state.update { it.copy(sleepAt = null, sleepAtEndOfTrack = true) }
        announcer.announce(app.getString(R.string.player_sleep_end_of_track))
    }

    fun cancelSleep(announce: Boolean = true) {
        sleepJob?.cancel()
        sleepJob = null
        controller?.volume = 1f
        val had = _state.value.sleepAt != null || _state.value.sleepAtEndOfTrack
        _state.update { it.copy(sleepAt = null, sleepAtEndOfTrack = false) }
        if (announce && had) announcer.announce(app.getString(R.string.player_sleep_cancelled))
    }

    private suspend fun fadeOutAndPause() {
        val c = controller ?: return
        val steps = 20
        for (i in steps downTo 0) {
            c.volume = i / steps.toFloat()
            delay(FADE_MS / steps)
        }
        c.pause()
        c.volume = 1f
        savePosition()
        _state.update { it.copy(sleepAt = null, sleepAtEndOfTrack = false) }
        announcer.announce(app.getString(R.string.player_sleep_done))
    }

    // --- Suivi ---

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            lastCue = ""
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _state.value.sleepAtEndOfTrack) {
                c.pause()
                c.seekTo(0)
                _state.update { it.copy(sleepAtEndOfTrack = false) }
                announcer.announce(app.getString(R.string.player_sleep_done))
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            val resume = mediaItem?.mediaId?.let(::resumePositionFor)
            if (resume != null) c.seekTo(resume)
            _state.update { it.copy(resumedFromMs = resume) }
            val text = announcementForCurrent(c)
            val resumeText = resume?.let { app.getString(R.string.player_resumed_at, TimeFormatter.spoken(it)) }
            announcer.announce(listOfNotNull(text, resumeText).joinToString(" "))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                announcer.announce(app.getString(R.string.player_headphones_unplugged))
            }
            if (!playWhenReady) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                controller?.currentMediaItem?.mediaId?.let { forget(it) }
                announcer.announce(app.getString(R.string.player_ended))
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            val name = currentTitle(c)
            feedback.failure()
            val reason = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                -> R.string.player_error_format
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                -> R.string.player_error_network
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> R.string.player_error_missing
                else -> R.string.player_error_generic
            }
            announcer.announce(app.getString(reason, name))
            // On passe à la suite plutôt que de rester bloqué.
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            if (!settings.readSubtitles || !_screenOpen.value) return
            val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            if (text.isNotEmpty() && text != lastCue) {
                lastCue = text
                announcer.announce(text)
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                val c = controller ?: continue
                if (c.mediaItemCount == 0) continue
                _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
                // Toutes les 5 s pendant la lecture : position de reprise des longs fichiers.
                if (c.isPlaying && ++n % 10 == 0) savePosition()
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            _state.update { PlayerUi(sleepAt = it.sleepAt, sleepAtEndOfTrack = it.sleepAtEndOfTrack) }
            return
        }
        val meta = c.mediaMetadata
        val id = c.currentMediaItem?.mediaId.orEmpty()
        val tracks = c.currentTracks
        _state.update { old ->
            old.copy(
                active = true,
                title = currentTitle(c),
                artist = meta.artist?.toString()?.ifBlank { null },
                album = meta.albumTitle?.toString()?.ifBlank { null },
                index = c.currentMediaItemIndex,
                count = c.mediaItemCount,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                speed = c.playbackParameters.speed,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatChoice.ALL
                    Player.REPEAT_MODE_ONE -> RepeatChoice.ONE
                    else -> RepeatChoice.OFF
                },
                shuffle = c.shuffleModeEnabled,
                isVideo = MediaKinds.isVideo(id) || c.videoSize.width > 0,
                queue = (0 until c.mediaItemCount).map { i ->
                    val item = c.getMediaItemAt(i)
                    val title = if (i == c.currentMediaItemIndex) currentTitle(c) else item.mediaMetadata.displayTitle?.toString() ?: item.mediaId
                    QueueItem(item.mediaId, title, if (i == c.currentMediaItemIndex) c.duration else C.TIME_UNSET)
                },
                audioTracks = options(tracks, C.TRACK_TYPE_AUDIO),
                textTracks = options(tracks, C.TRACK_TYPE_TEXT),
                subtitlesOff = C.TRACK_TYPE_TEXT in c.trackSelectionParameters.disabledTrackTypes ||
                    tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected },
            )
        }
    }

    private fun currentTitle(c: Player): String {
        val meta = c.mediaMetadata
        return meta.title?.toString()?.ifBlank { null }
            ?: meta.displayTitle?.toString()?.ifBlank { null }
            ?: c.currentMediaItem?.mediaId?.substringAfterLast('/').orEmpty()
    }

    /** « Lecture : Chanson, piste 3 sur 12 ». */
    private fun announcementForCurrent(c: Player): String {
        val title = currentTitle(c)
        return if (c.mediaItemCount > 1) {
            app.getString(R.string.player_now_playing_n, title, c.currentMediaItemIndex + 1, c.mediaItemCount)
        } else {
            app.getString(R.string.player_now_playing, title)
        }
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        val result = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { g, group ->
            if (group.type != type) return@forEachIndexed
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                result += TrackOption(g, t, trackLabel(group.getTrackFormat(t), type, result.size + 1), group.isTrackSelected(t))
            }
        }
        return result
    }

    /** « Français, 5.1, audiodescription », « Anglais, forcés »… */
    private fun trackLabel(format: Format, type: Int, number: Int): String {
        val language = format.language?.takeIf { it != C.LANGUAGE_UNDETERMINED }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { c -> c.uppercase() }
        }
        val parts = buildList {
            add(format.label ?: language ?: app.getString(R.string.player_track_n, number))
            if (format.label != null && language != null && language !in format.label!!) add(language)
            if (type == C.TRACK_TYPE_AUDIO) {
                when (format.channelCount) {
                    1 -> add(app.getString(R.string.player_mono))
                    2 -> add(app.getString(R.string.player_stereo))
                    6 -> add("5.1")
                    8 -> add("7.1")
                }
            }
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add(app.getString(R.string.player_audio_description))
            if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) add(app.getString(R.string.player_sdh))
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(app.getString(R.string.player_forced))
        }
        return parts.joinToString(", ")
    }

    // --- Reprise ---

    private fun resumePositionFor(id: String): Long? = positions[id]?.takeIf { it > MIN_RESUME_MS }

    /** Mémorise la position des fichiers longs (livres audio, podcasts, films). */
    private fun savePosition() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val duration = c.duration
        if (duration == C.TIME_UNSET || duration < LONG_MEDIA_MS) return
        val position = c.currentPosition
        scope.launch {
            preferences.setResumePosition(id, position.takeIf { it > MIN_RESUME_MS && duration - it > END_MARGIN_MS })
        }
    }

    private fun forget(id: String) {
        if (id in positions) scope.launch { preferences.setResumePosition(id, null) }
    }

    private fun fail(message: String) {
        feedback.failure()
        announcer.announce(message)
    }

    private companion object {
        const val FADE_MS = 10_000L
        const val LONG_MEDIA_MS = 20 * 60_000L
        const val MIN_RESUME_MS = 30_000L
        const val END_MARGIN_MS = 30_000L
    }
}
