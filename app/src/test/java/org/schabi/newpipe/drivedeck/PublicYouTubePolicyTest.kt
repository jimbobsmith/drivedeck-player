package org.schabi.newpipe.drivedeck

import org.junit.Assert.*
import org.junit.Test

class PublicYouTubePolicyTest {
    private val official = setOf("29d743afecc02f012121ad06759abac5fcab5812c5d0af01a4258346495f05c0")
    @Test fun callerMustOwnTheOsPackageAndTrustedSigner() {
        assertTrue(PublicYouTubePolicy.allowedCaller("com.drivedeck.g", setOf("com.drivedeck.g"), official, setOf("other"), false, false))
        assertFalse(PublicYouTubePolicy.allowedCaller("com.drivedeck.g", setOf("other.app"), official, official, true, false))
        assertFalse(PublicYouTubePolicy.allowedCaller("com.drivedeck.g", setOf("com.drivedeck.g"), setOf("fake"), official, false, false))
        assertFalse(PublicYouTubePolicy.allowedCaller("evil.app", setOf("evil.app"), official, official, true, false))
    }
    @Test fun debugTrustRequiresDebugBuildAndExactNonemptyOwnSignerSet() {
        val pkg = "com.drivedeck.g.test"
        assertTrue(PublicYouTubePolicy.allowedCaller(pkg, setOf(pkg), setOf("debug"), setOf("debug"), true, false))
        assertFalse(PublicYouTubePolicy.allowedCaller(pkg, setOf(pkg), setOf("debug"), setOf("debug"), false, false))
        assertFalse(PublicYouTubePolicy.allowedCaller(pkg, setOf(pkg), emptySet(), emptySet(), true, false))
        assertFalse(PublicYouTubePolicy.allowedCaller(pkg, setOf(pkg), setOf("debug", "other"), setOf("debug"), true, false))
    }
    @Test fun homeAndSearchHaveNoPrivateLibraryOrArbitraryUrlOperation() {
        assertNull(PublicYouTubePolicy.query("home"))
        assertEquals("people talking", PublicYouTubePolicy.query("search:people+talking"))
        for (value in listOf("history", "subscriptions", "https://example.com", "search:", "search:a%0Ab"))
            assertTrue(runCatching { PublicYouTubePolicy.query(value) }.isFailure)
        assertTrue(PublicYouTubePolicy.videoId("abcdefghijk"))
        assertFalse(PublicYouTubePolicy.videoId("abcdefghijk?auth=secret"))
    }
    @Test fun onlyPublicHttpsYoutubeThumbnailsPass() {
        assertNotNull(PublicYouTubePolicy.thumbnail("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"))
        for (value in listOf("http://i.ytimg.com/a", "https://127.0.0.1/a", "https://user@i.ytimg.com/a", "https://evil.test/a"))
            assertNull(PublicYouTubePolicy.thumbnail(value))
        assertEquals("a b", PublicYouTubePolicy.text("a\nb"))
        assertEquals(256, PublicYouTubePolicy.text("x".repeat(300)).length)
    }
}
