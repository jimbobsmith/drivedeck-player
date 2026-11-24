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
        assertEquals(PlayerLibraryPolicy.Page.Continue, PlayerLibraryPolicy.page("lib:continue"))
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

    @Test fun continueKeepsOnlyUnfinishedVideosWithTheSamePipePipeRule() {
        assertFalse(PlayerLibraryPolicy.unfinished(4_999, 600))            // below PipePipe's save threshold
        assertTrue(PlayerLibraryPolicy.unfinished(300_000, 600))           // halfway through 10 minutes
        assertFalse(PlayerLibraryPolicy.unfinished(545_000, 600))          // last minute and past 3/4: finished
        assertFalse(PlayerLibraryPolicy.unfinished(601_000, 600))          // completed (duration + 1 s)
        assertTrue(PlayerLibraryPolicy.unfinished(50_000, 100))            // short video: last minute but not 3/4 in
        assertTrue(PlayerLibraryPolicy.unfinished(90_000, -1))             // synced before the length is known
        assertEquals(PlayerLibraryPolicy.Page.Continue.let(PlayerLibraryPolicy::emptyMessage), PlayerLibraryPolicy.EMPTY_CONTINUE)
    }

    @Test fun resumeLabelsFitTheSubtitle() {
        assertEquals("Channel · 5:00 left", PlayerLibraryPolicy.resumeLabel("Channel", 300_000, 600))
        assertEquals("1:02:03 left", PlayerLibraryPolicy.resumeLabel("", 0, 3723))
        assertEquals("Channel · Resume at 3:05", PlayerLibraryPolicy.resumeLabel("Channel", 185_000, 0))
        assertEquals("0:00", PlayerLibraryPolicy.clock(-5))
    }
}
