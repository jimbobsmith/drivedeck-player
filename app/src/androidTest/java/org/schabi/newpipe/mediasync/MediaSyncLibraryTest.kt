package org.schabi.newpipe.mediasync

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import java.util.UUID

/** Private in-memory Room only. The runner additionally verifies the task-owned emulator name. */
@RunWith(AndroidJUnit4::class)
class MediaSyncLibraryTest {
    private inline fun <T> AppDatabase.withDatabase(block: (AppDatabase) -> T): T = try { block(this) } finally { close() }
    private val video = "aBcDeFgH123"
    private val other = "ZyxWVuts987"
    private fun database(): AppDatabase {
        assertTrue("Emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        return Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    private fun playlist(id: String = UUID.randomUUID().toString(), name: String = "Trips", items: List<String> = listOf(video, other, video)) = JSONObject().put("kind", "playlist").put("provider", "youtube").put("version", 2).put("id", id).put("name", name).put("items", JSONArray(items))
    private fun apply(lib: MediaSyncLibrary, p: JSONObject, remove: Boolean = false) = lib.project(JSONObject().put("payload", p).put("operation", if (remove) "remove" else "put"))
    @Test fun realRoomPlaylistRoundTripRenameOrderDeleteAndStableMapping() {
        database().withDatabase { db -> db.runInTransaction {
            val lib = MediaSyncLibrary(db); val p = playlist(); apply(lib, p)
            var actual = lib.snapshot().single { it.getString("kind") == "playlist" }; assertEquals(p.getString("id"), actual.getString("id")); assertEquals(p.getJSONArray("items").toString(), actual.getJSONArray("items").toString())
            val uid = db.query("SELECT uid FROM playlists WHERE name='Trips'", null).use { it.moveToFirst(); it.getLong(0) }
            val changed = playlist(p.getString("id"), "Renamed", listOf(video, video)); apply(lib, changed)
            actual = MediaSyncLibrary(db).snapshot().single { it.getString("kind") == "playlist" }; assertEquals("Renamed", actual.getString("name")); assertEquals(changed.getJSONArray("items").toString(), actual.getJSONArray("items").toString())
            assertEquals(uid, db.query("SELECT uid FROM playlists WHERE name='Renamed'", null).use { it.moveToFirst(); it.getLong(0) })
            db.mediaSyncStateDAO().write(MediaSyncState(body = "test state")); db.mediaSyncStateDAO().clear()
            assertEquals(changed.getString("id"), MediaSyncLibrary(db).snapshot().single { it.getString("kind") == "playlist" }.getString("id"))
            apply(lib, changed, true); assertFalse(lib.snapshot().any { it.getString("kind") == "playlist" })
        } }
    }
    @Test fun sharedWatchLaterNeverAdoptsAnExistingDisplayName() {
        database().withDatabase { db -> db.runInTransaction {
            db.playlistDAO().insert(PlaylistEntity("Watch Later", "", 0)); val lib = MediaSyncLibrary(db); lib.snapshot()
            val p = JSONObject().put("kind", "watch-later").put("provider", "youtube").put("version", 2).put("id", "watch-later").put("items", JSONArray(listOf(video)))
            apply(lib, p); val rows = lib.snapshot(); assertEquals(2, rows.count { it.getString("kind") in listOf("playlist", "watch-later") })
            assertEquals(video, rows.single { it.getString("kind") == "watch-later" }.getJSONArray("items").getString(0))
            apply(lib, JSONObject(p.toString()).put("items", JSONArray())); assertEquals(0, lib.snapshot().single { it.getString("kind") == "watch-later" }.getJSONArray("items").length())
        } }
    }
    @Test fun historyPreservesDatesCountsAndResumeAndNeverCountsAnImportedView() {
        database().withDatabase { db -> db.runInTransaction {
            val lib = MediaSyncLibrary(db); val p = JSONObject().put("kind", "history").put("provider", "youtube").put("version", 2).put("id", video).put("watchedAtMs", 1000)
            apply(lib, p); val uid = db.query("SELECT uid FROM streams", null).use { it.moveToFirst(); it.getLong(0) }; db.streamStateDAO().upsert(StreamStateEntity(uid, 12345))
            db.openHelper.writableDatabase.execSQL("INSERT INTO stream_history(stream_id,access_date,repeat_count) VALUES (?,?,?)", arrayOf(uid, 2000L, 3))
            apply(lib, JSONObject(p.toString()).put("watchedAtMs", 3000)); apply(lib, p)
            assertEquals(3000L, lib.snapshot().single { it.getString("kind") == "history" }.getLong("watchedAtMs"))
            assertEquals(3L, db.query("SELECT SUM(repeat_count) FROM stream_history", null).use { it.moveToFirst(); it.getLong(0) })
            assertEquals(3, db.query("SELECT COUNT(*) FROM stream_history", null).use { it.moveToFirst(); it.getInt(0) })
            apply(lib, p, true); assertFalse(lib.snapshot().any { it.getString("kind") == "history" })
            assertEquals(12345L, db.query("SELECT progress_time FROM stream_state WHERE stream_id=?", arrayOf(uid)).use { it.moveToFirst(); it.getLong(0) })
        } }
    }
    @Test fun portablePayloadRejectsOversizedMixedAndUnknownFields() {
        for (p in listOf(playlist(items = List(201) { video }), playlist(items = listOf("https://example.invalid/private")), playlist().put("version", 3), playlist().put("cookie", "private"))) {
            try { MediaSyncCrypto.validatePayload(p); fail("Expected bounded portable schema rejection") } catch (_: SyncFailure) { }
        }
        MediaSyncCrypto.validatePayload(playlist(items = List(200) { video }))
    }
    @Test fun actualServiceLibraryFlow() {
        assertTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments(); val name = args.getString("libraryDatabase") ?: error("Explicit isolated database required")
        assertTrue(name.matches(Regex("dd-library-[0-9a-f-]{36}\\.sqlite")))
        val config = JSONObject(java.io.File(context.cacheDir, "dd-library-config.json").readText())
        assertEquals(config.getString("permit"), args.getString("libraryPermit")); config.remove("permit")
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        db.withDatabase {
            val client = MediaSyncClient(db, config); client.enableLibrary(); val lib = MediaSyncLibrary(db)
            val id = "11111111-1111-4111-8111-111111111111"
            when (args.getString("libraryPhase")) {
                "receive" -> {
                    val first = client.sync(); if (first.getString("code") == "conflict") assertEquals("synced", client.sync("use-remote").getString("code")) else assertEquals("synced", first.getString("code"))
                    val list = lib.snapshot().single { it.getString("kind") == "playlist" }; assertEquals(id, list.getString("id")); assertEquals("Desktop trip", list.getString("name")); assertEquals(JSONArray(listOf(video, other, video)).toString(), list.getJSONArray("items").toString())
                    assertEquals(video, lib.snapshot().single { it.getString("kind") == "watch-later" }.getJSONArray("items").getString(0))
                    assertEquals(1000L, lib.snapshot().single { it.getString("kind") == "history" }.getLong("watchedAtMs"))
                    db.runInTransaction { apply(lib, playlist(id, "Android trip", listOf(other, video, video))) }
                    assertEquals("synced", client.sync().getString("code"))
                }
                "offline" -> {
                    db.runInTransaction { apply(lib, playlist(id, "Offline Android", listOf(video))) }
                    assertEquals("offline", client.sync().getString("code")); assertTrue(client.status().getInt("pending") > 0)
                }
                "retry-conflict" -> {
                    assertEquals("conflict", client.sync().getString("code")); assertEquals("Offline Android", lib.snapshot().single { it.getString("kind") == "playlist" }.getString("name"))
                    assertEquals("synced", client.sync("keep-local").getString("code"))
                    assertEquals(0, client.status().getInt("pending"))
                }
                else -> error("Explicit phase required")
            }
        }
    }

}
