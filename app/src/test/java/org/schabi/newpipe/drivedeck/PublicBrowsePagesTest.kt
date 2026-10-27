package org.schabi.newpipe.drivedeck

import org.junit.Assert.*
import org.junit.Test

class PublicBrowsePagesTest {
    @Test fun tokenIsOpaqueAndCannotBeReadByAnotherUid() {
        val pages = PublicBrowsePages<String>({ 0 })
        val id = pages.add(10) { "public page" }
        assertTrue(id.matches(Regex("page:[a-f0-9-]{36}")))
        assertTrue(runCatching { pages.get(id, 11) }.isFailure)
        assertEquals("public page", pages.get(id, 10))
    }
    @Test fun successfulContinuationIsStableButFailuresCanRetry() {
        val pages = PublicBrowsePages<String>({ 0 })
        var calls = 0
        val id = pages.add(1) { if (++calls == 1) error("offline") else "page" }
        assertTrue(runCatching { pages.get(id, 1) }.isFailure)
        assertEquals("page", pages.get(id, 1))
        assertEquals("page", pages.get(id, 1))
        assertEquals(2, calls)
    }
    @Test fun expiredAndEvictedTokensAreRejected() {
        var now = 0L
        val pages = PublicBrowsePages<String>({ now }, capacity = 2, lifetimeMs = 100)
        val first = pages.add(1) { "1" }
        val second = pages.add(1) { "2" }
        pages.add(1) { "3" }
        assertTrue(runCatching { pages.get(first, 1) }.isFailure)
        assertEquals("2", pages.get(second, 1))
        now = 100
        assertTrue(runCatching { pages.get(second, 1) }.isFailure)
    }
    @Test fun closeRejectsExistingTokensAndLateRegistrations() {
        val pages = PublicBrowsePages<String>({ 0 })
        val id = pages.add(1) { "page" }
        pages.close()
        assertTrue(runCatching { pages.get(id, 1) }.isFailure)
        assertTrue(runCatching { pages.add(1) { "late page" } }.isFailure)
    }
    @Test fun closeDuringExtractionCannotReturnAStalePage() {
        val pages = PublicBrowsePages<String>({ 0 })
        val id = pages.add(1) { pages.close(); "late page" }
        assertTrue(runCatching { pages.get(id, 1) }.isFailure)
    }
}
