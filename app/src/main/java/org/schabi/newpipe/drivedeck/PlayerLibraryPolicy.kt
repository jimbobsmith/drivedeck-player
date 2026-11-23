package org.schabi.newpipe.drivedeck

/**
 * Read-only DD PipePipe library pages for DriveDeck (drivedeckg #273): new videos from
 * subscriptions, watch history and local playlists. Nothing here writes Room, starts
 * playback or touches the network; the owner turns sharing on in History settings.
 */
internal object PlayerLibraryPolicy {
    const val CAPABILITY = "com.drivedeck.youtube.library"
    const val VERSION = "v1"
    const val PREFIX = "lib:"
    const val SUBSCRIPTIONS = "lib:subscriptions"
    const val HISTORY = "lib:history"
    const val PLAYLISTS = "lib:playlists"
    /** Unfinished videos, newest watch first, with the saved position (drivedeck-player #54). */
    const val CONTINUE = "lib:continue"
    const val CONTINUE_CAPABILITY = "com.drivedeck.youtube.continue"
    const val CONTINUE_VERSION = "v1"
    /** Optional MediaDescription extras on video items: saved position and length, in milliseconds. */
    const val EXTRA_PROGRESS_MS = "com.drivedeck.youtube.progress_ms"
    const val EXTRA_DURATION_MS = "com.drivedeck.youtube.duration_ms"
    /** A calm "nothing here" item: newer DriveDeck shows it without Retry, older versions drop it and show their own empty text. */
    const val EMPTY_ID = "status:empty"
    /** PipePipe saves a position only after this much playback (StreamStateEntity). */
    const val MIN_PROGRESS_MS = 5_000L
    private const val PLAYLIST = "lib:playlist:"
    const val MAX_ITEMS = 500
    const val CONSENT_KEY = "drivedeck_share_library"
    const val DENIED = "Turn on Share library with DriveDeck in DD PipePipe settings (History)"
    const val EMPTY_SUBSCRIPTIONS = "No new videos yet. Open Subscriptions in DD PipePipe to refresh them."
    const val EMPTY_HISTORY = "No watch history yet"
    const val EMPTY_PLAYLISTS = "No playlists yet. Create one in DD PipePipe."
    const val EMPTY_PLAYLIST = "This playlist is empty"
    const val EMPTY_CONTINUE = "Nothing to continue. Videos you stop partway through, on any of your synced devices, appear here."

    sealed interface Page {
        data object Subscriptions : Page
        data object History : Page
        data object Playlists : Page
        data object Continue : Page
        data class Playlist(val id: Long) : Page
    }

    fun isLibrary(parent: String) = parent.startsWith(PREFIX)

    fun page(parent: String): Page? = when {
        parent == SUBSCRIPTIONS -> Page.Subscriptions
        parent == HISTORY -> Page.History
        parent == PLAYLISTS -> Page.Playlists
        parent == CONTINUE -> Page.Continue
        parent.startsWith(PLAYLIST) -> parent.removePrefix(PLAYLIST)
            .takeIf { it.length in 1..18 && it.all(Char::isDigit) }?.toLongOrNull()?.let(Page::Playlist)
        else -> null
    }

    fun playlistId(id: Long) = "$PLAYLIST$id"

    fun emptyMessage(page: Page) = when (page) {
        Page.Subscriptions -> EMPTY_SUBSCRIPTIONS
        Page.History -> EMPTY_HISTORY
        Page.Playlists -> EMPTY_PLAYLISTS
        is Page.Playlist -> EMPTY_PLAYLIST
        Page.Continue -> EMPTY_CONTINUE
    }

    /**
     * Same rule as StreamStateEntity.isFinished: finished means within the last minute and at least 3/4 in.
     * An unknown length (synced before the video was opened here) counts as unfinished.
     */
    fun unfinished(progressMs: Long, durationSeconds: Long): Boolean {
        if (progressMs < MIN_PROGRESS_MS) return false
        if (durationSeconds <= 0) return true
        val length = durationSeconds * 1000
        return !(progressMs >= length - 60_000 && progressMs >= length * 3 / 4)
    }

    /** "Channel · 12:34 left", or "Channel · Resume at 3:05" when the length is unknown. Fits older displays' subtitle. */
    fun resumeLabel(uploader: String, progressMs: Long, durationSeconds: Long): String {
        val position = if (durationSeconds > 0) "${clock(durationSeconds * 1000 - progressMs)} left" else "Resume at ${clock(progressMs)}"
        return if (uploader.isBlank()) position else "$uploader · $position"
    }

    fun clock(ms: Long): String {
        val total = maxOf(ms, 0) / 1000
        val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Latest watch first; positions synced without a local history row go last. */
    const val CONTINUE_QUERY = """
        SELECT s.url, s.title, s.uploader, s.thumbnail_url, s.duration, st.progress_time
        FROM streams s
        INNER JOIN stream_state st ON st.stream_id = s.uid
        LEFT JOIN (SELECT stream_id, MAX(access_date) AS latest FROM stream_history GROUP BY stream_id) h ON h.stream_id = s.uid
        WHERE s.service_id = 0 AND st.progress_time >= $MIN_PROGRESS_MS
        ORDER BY h.latest IS NULL, h.latest DESC, s.uid DESC
        LIMIT 2000
    """

    fun countLabel(count: Long) = if (count == 1L) "1 video" else "$count videos"
}
