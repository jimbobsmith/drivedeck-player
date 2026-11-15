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
    private const val PLAYLIST = "lib:playlist:"
    const val MAX_ITEMS = 500
    const val CONSENT_KEY = "drivedeck_share_library"
    const val DENIED = "Turn on Share library with DriveDeck in DD PipePipe settings (History)"
    const val EMPTY_SUBSCRIPTIONS = "No new videos yet. Open Subscriptions in DD PipePipe to refresh them."
    const val EMPTY_HISTORY = "No watch history yet"
    const val EMPTY_PLAYLISTS = "No playlists yet. Create one in DD PipePipe."
    const val EMPTY_PLAYLIST = "This playlist is empty"

    sealed interface Page {
        data object Subscriptions : Page
        data object History : Page
        data object Playlists : Page
        data class Playlist(val id: Long) : Page
    }

    fun isLibrary(parent: String) = parent.startsWith(PREFIX)

    fun page(parent: String): Page? = when {
        parent == SUBSCRIPTIONS -> Page.Subscriptions
        parent == HISTORY -> Page.History
        parent == PLAYLISTS -> Page.Playlists
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
    }

    fun countLabel(count: Long) = if (count == 1L) "1 video" else "$count videos"
}
