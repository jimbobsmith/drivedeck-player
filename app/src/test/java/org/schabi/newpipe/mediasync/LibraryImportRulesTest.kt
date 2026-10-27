package org.schabi.newpipe.mediasync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryImportRulesTest {
    private val channel = "UC" + "a".repeat(22)
    private val video = "abcdefghijk"
    private fun profile(id: String = "one") = JSONObject().put("_id", id).put("name", "Science").put("bgColor", "#abcdef").put("textColor", "#ffffff").put("subscriptions", JSONArray().put(JSONObject().put("id", channel).put("name", "PRIVATE TITLE").put("thumbnail", "https://private.invalid")))
    @Test fun ndjsonWithOrWithoutTrailingNewlineKeepsAllProfilesAndIndependentIds() {
        val lines = profile("allChannels").toString() + "\n" + profile().toString() + "\n" + profile("two").toString()
        for (text in listOf(lines, lines + "\n")) {
            val result = LibraryImportRules.parse(text)
            assertEquals(2, result.records.count { it.getString("kind") == "profile" })
            assertEquals(2, result.records.count { it.getString("kind") == "profile-member" })
            assertEquals(1, result.records.count { it.getString("kind") == "subscription" })
            assertEquals("#ABCDEF", result.records.first { it.getString("kind") == "profile" }.getString("bgColor"))
            assertFalse(result.records.toString().contains("private.invalid"))
            assertTrue(LibraryImportRules.merge(result.records, result.records).isEmpty())
        }
    }
    @Test fun playlistsPreserveDuplicateOccurrencesAndOnlyBuiltInIdentityBecomesWatchLater() {
        fun list(id: String) = JSONObject().put("_id", id).put("playlistName", "Watch Later").put("videos", JSONArray().put(JSONObject().put("videoId", video)).put(JSONObject().put("videoId", video)))
        val parsed = LibraryImportRules.parse(JSONArray().put(list("ordinary")).put(list("watchLater")).toString())
        assertEquals(1, parsed.records.count { it.getString("kind") == "playlist" })
        assertEquals(1, parsed.records.count { it.getString("kind") == "watch-later" })
        assertTrue(parsed.records.all { it.getJSONArray("items").length() == 2 })
        assertTrue(LibraryImportRules.merge(parsed.records, parsed.records).isEmpty())
    }
    @Test fun historyUsesMillisecondsAndResumeSecondsWithoutOverwritingDeliberateProgress() {
        val parsed = LibraryImportRules.parse(JSONObject().put("videoId", video).put("timeWatched", 123000).put("watchProgress", 1.234).toString())
        val resume = parsed.records.first { it.getString("kind") == "resume" }
        assertEquals(1234L, resume.getLong("positionMs"))
        val existing = parsed.records.map { if (it.getString("kind") == "resume") JSONObject(it.toString()).put("positionMs", 10) else it }
        assertTrue(LibraryImportRules.merge(existing, parsed.records).isEmpty())
    }
    @Test fun unsupportedProvidersAndAliasesAreReportedWithoutLookup() {
        val result = LibraryImportRules.parse(JSONObject().put("subscriptions", JSONArray().put(JSONObject().put("service_id", 0).put("url", "https://www.youtube.com/channel/$channel")).put(JSONObject().put("service_id", 3).put("url", "https://private.invalid")).put(JSONObject().put("service_id", 0).put("url", "https://www.youtube.com/@alias"))).toString())
        assertEquals(1, result.records.size); assertEquals(2, result.unsupported)
        assertNull(LibraryImportRules.canonical("https://user:pass@www.youtube.com/channel/$channel", true))
    }
    @Test fun backupSchemaAllowsOnlyOrdinaryTables() {
        assertTrue(LibraryImportRules.plainTable("CREATE TABLE subscriptions(uid INTEGER,url TEXT)"))
        assertFalse(LibraryImportRules.plainTable("CREATE VIRTUAL TABLE subscriptions USING fts5(url)"))
        assertFalse(LibraryImportRules.plainTable("CREATE TABLE subscriptions(uid INTEGER,url TEXT AS (randomblob(100000000)))"))
    }
    @Test fun malformedAndOversizedDataFailsWithoutPartialPlan() {
        for (text in listOf("{bad", profile().put("name", "x".repeat(161)).toString(), profile().put("name", "bad\nname").toString())) {
            try { LibraryImportRules.parse(text); fail("Must reject malformed or unsupported records") } catch (_: Exception) { }
        }
    }
    @org.junit.Test fun emptyExportsAreNoOps() { for (text in listOf("\n", "[]")) org.junit.Assert.assertTrue(LibraryImportRules.parse(text).records.isEmpty()) }
}
