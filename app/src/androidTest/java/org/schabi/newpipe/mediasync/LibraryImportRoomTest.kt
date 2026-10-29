package org.schabi.newpipe.mediasync

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity

/** Synthetic Room only. Compile now; execute only in an explicitly assigned dedicated emulator slot. */
@RunWith(AndroidJUnit4::class)
class LibraryImportRoomTest {
    private val channel = "UC" + "a".repeat(22)
    private fun database(block: (AppDatabase) -> Unit) {
        assertTrue("Dedicated emulator only", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }
    private fun profile() = LibraryImportRules.parse(JSONObject().put("_id", "public-science").put("name", "Science").put("bgColor", "#112233").put("textColor", "#FFFFFF").put("subscriptions", JSONArray().put(JSONObject().put("id", channel))).toString())
    @Test fun previewIsReadOnlyAndRepeatedCommitKeepsOneCategoryAndSubscription() = database { db ->
        val importer = LibraryImporter(db); val records = profile().records
        assertEquals(3, importer.preview(records).size)
        assertTrue(importer.snapshot().isEmpty())
        assertEquals(3, importer.commit(records).size)
        assertTrue(importer.commit(records).isEmpty())
        assertEquals(1, importer.snapshot().count { it.getString("kind") == "profile" })
        assertEquals(1, importer.snapshot().count { it.getString("kind") == "subscription" })
        assertEquals(1, importer.snapshot().count { it.getString("kind") == "profile-member" })
    }
    @Test fun lateProjectionFailureRollsBackWholeMergeAndMappings() = database { db ->
        val original = SubscriptionEntity().apply { serviceId = 0; url = "https://www.youtube.com/channel/UC" + "b".repeat(22); name = "Keep"; avatarUrl = ""; notificationMode = 0 }
        db.subscriptionDAO().insert(original)
        val before = LibraryImporter(db).snapshot()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_import BEFORE INSERT ON playlists BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        val records = profile().records + LibraryImportRules.parse(JSONObject().put("_id", "list-one").put("playlistName", "Example").put("videos", JSONArray().put(JSONObject().put("videoId", "abcdefghijk"))).toString()).records
        try { LibraryImporter(db).commit(records); fail("Expected projection error") } catch (_: Exception) { }
        val after = LibraryImporter(db).snapshot()
        assertEquals(before.map { it.toString() }, after.map { it.toString() })
        assertEquals(0, db.query("SELECT COUNT(*) FROM dd_media_sync", null).use { it.moveToFirst(); it.getInt(0) })
    }
    @Test fun unrelatedNonYoutubePlaylistDoesNotBlockCategoryImport() = database { db ->
        db.openHelper.writableDatabase.execSQL("INSERT INTO playlists(uid,name,thumbnail_url,display_index) VALUES(90,'Keep local','',0)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO streams(uid,service_id,url,title,stream_type,duration,uploader,is_paid) VALUES(90,1,'https://soundcloud.com/example','Keep','AUDIO_STREAM',1,'Example',0)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO playlist_stream_join(playlist_id,stream_id,join_index) VALUES(90,90,0)")
        LibraryImporter(db).commit(profile().records)
        assertEquals(1, db.query("SELECT COUNT(*) FROM playlist_stream_join WHERE playlist_id=90 AND stream_id=90", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, LibraryImporter(db).snapshot().count { it.getString("kind") == "profile" })
    }
}
