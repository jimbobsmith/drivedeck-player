package org.schabi.newpipe.drivedeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLibraryPolicyTest {
    @Test fun libraryPagesAreTheOnlyAcceptedParents() {
        assertEquals(PlayerLibraryPolicy.Page.Subscriptions, PlayerLibraryPolicy.page("lib:subscriptions"))
        assertEquals(PlayerLibraryPolicy.Page.History, PlayerLibraryPolicy.page("lib:history"))
        assertEquals(PlayerLibraryPolicy.Page.Playlists, PlayerLibraryPolicy.page("lib:playlists"))
        assertEquals(PlayerLibraryPolicy.Page.Playlist(42), PlayerLibraryPolicy.page(PlayerLibraryPolicy.playlistId(42)))
        for (parent in listOf("lib:", "lib:feed", "lib:playlist:", "lib:playlist:-1", "lib:playlist:1x",
                "lib:playlist:${"9".repeat(19)}", "lib:playlist:1/../2", "home", "search:x")) {
            assertNull(parent, PlayerLibraryPolicy.page(parent))
        }
    }

    @Test fun onlyLibraryParentsRouteToTheLibrary() {
        assertTrue(PlayerLibraryPolicy.isLibrary("lib:history"))
        assertFalse(PlayerLibraryPolicy.isLibrary("home"))
        assertFalse(PlayerLibraryPolicy.isLibrary("search:lib:history"))
        assertFalse(PlayerLibraryPolicy.isLibrary("page:12345678-1234-1234-1234-123456789abc"))
    }

    @Test fun emptyPagesExplainWhatToDo() {
        assertEquals(PlayerLibraryPolicy.EMPTY_PLAYLIST, PlayerLibraryPolicy.emptyMessage(PlayerLibraryPolicy.Page.Playlist(1)))
        assertTrue(PlayerLibraryPolicy.emptyMessage(PlayerLibraryPolicy.Page.Subscriptions).contains("refresh"))
        assertEquals("1 video", PlayerLibraryPolicy.countLabel(1))
        assertEquals("3 videos", PlayerLibraryPolicy.countLabel(3))
    }
}
