package org.schabi.newpipe.mediasync

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.playlist.model.PlaylistEntity
import org.schabi.newpipe.database.playlist.model.PlaylistStreamEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.UUID
import org.schabi.newpipe.mediasync.MediaSyncCrypto.check

/** Portable playlists use UUIDs; only this local mapping knows Room UIDs. Row 2 survives disconnect. */
class MediaSyncLibrary(private val db: AppDatabase) {
    private fun mapping(): JSONObject = db.query("SELECT body FROM dd_media_sync WHERE id=2", null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() }
    private fun save(map: JSONObject) { db.mediaSyncStateDAO().write(MediaSyncState(id = 2, body = map.toString())) }
    private fun exists(uid: Long) = db.query("SELECT uid FROM playlists WHERE uid=?", arrayOf(uid)).use { it.moveToFirst() }
    private fun ensureWatch(map: JSONObject): Long {
        var uid = map.optLong("watch-later", -1)
        if (uid < 1 || !exists(uid)) { uid = db.playlistDAO().insert(PlaylistEntity("Watch Later (synced)", "", 0)); map.put("watch-later", uid); save(map) }
        return uid
    }
    fun snapshot(): List<JSONObject> {
        val map = mapping(); val watchUid = ensureWatch(map); val result = mutableListOf<JSONObject>()
        db.query("SELECT uid,name FROM playlists LIMIT 5001", null).use { c ->
            var count = 0
            while (c.moveToNext()) {
                check(++count <= 5000, "library_limit"); val uid = c.getLong(0)
                val id = if (uid == watchUid) "watch-later" else map.keys().asSequence().firstOrNull { it != "watch-later" && map.optLong(it, -1) == uid } ?: UUID.randomUUID().toString().also { map.put(it, uid); save(map) }
                val items = JSONArray()
                db.query("SELECT s.service_id,s.url FROM playlist_stream_join j JOIN streams s ON s.uid=j.stream_id WHERE j.playlist_id=? ORDER BY j.join_index LIMIT 201", arrayOf(uid)).use { rows ->
                    while (rows.moveToNext()) {
                        val videoId = if (rows.getInt(0) == 0) Regex("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})").matchEntire(rows.getString(1))?.groupValues?.get(1) else null
                        check(videoId != null, "playlist_not_portable"); items.put(videoId)
                    }
                }
                val p = JSONObject().put("kind", if (id == "watch-later") "watch-later" else "playlist").put("provider", "youtube").put("version", 2).put("id", id).put("items", items)
                if (id != "watch-later") p.put("name", c.getString(1))
                result.add(MediaSyncCrypto.validatePayload(p))
            }
        }
        db.query("SELECT s.url,MAX(h.access_date) FROM streams s JOIN stream_history h ON s.uid=h.stream_id WHERE s.service_id=0 GROUP BY s.uid LIMIT 5001", null).use { c ->
            var count = 0
            while (c.moveToNext()) {
                check(++count <= 5000, "library_limit")
                val id = Regex("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})").matchEntire(c.getString(0))?.groupValues?.get(1) ?: continue
                if (c.getLong(1) <= 0) continue
                result.add(MediaSyncCrypto.validatePayload(JSONObject().put("kind", "history").put("provider", "youtube").put("version", 2).put("id", id).put("watchedAtMs", c.getLong(1))))
            }
        }
        return result
    }
    private fun stream(id: String): Long {
        val url = "https://www.youtube.com/watch?v=$id"
        val uid = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else -1 }
        return if (uid > 0) uid else db.streamDAO().insert(StreamEntity(serviceId = 0, url = url, title = "Synced video: $id", streamType = StreamType.VIDEO_STREAM, duration = -1, uploader = ""))
    }
    fun newerHistory(p: JSONObject): JSONObject? {
        val latest = db.query("SELECT MAX(h.access_date) FROM streams s JOIN stream_history h ON s.uid=h.stream_id WHERE s.service_id=0 AND s.url=?", arrayOf("https://www.youtube.com/watch?v=" + p.getString("id"))).use { it.moveToFirst(); if (it.isNull(0)) 0L else it.getLong(0) }
        return if (latest > p.getLong("watchedAtMs")) JSONObject(p.toString()).put("watchedAtMs", latest) else null
    }
    fun project(record: JSONObject) {
        val p = MediaSyncCrypto.validatePayload(record.getJSONObject("payload")); val id = p.getString("id"); val remove = record.getString("operation") == "remove"
        if (p.getString("kind") == "history") {
            val url = "https://www.youtube.com/watch?v=$id"
            val found = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else -1 }
            if (remove) { if (found > 0) db.streamHistoryDAO().deleteStreamHistory(found); return }
            val uid = if (found > 0) found else stream(id)
            // Keep existing dated rows and repeat counts. A remote record is a zero-count observation.
            val latest = db.query("SELECT MAX(access_date) FROM stream_history WHERE stream_id=?", arrayOf(uid)).use { it.moveToFirst(); if (it.isNull(0)) 0L else it.getLong(0) }
            if (p.getLong("watchedAtMs") > latest) db.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO stream_history(stream_id,access_date,repeat_count) VALUES (?,?,0)", arrayOf(uid, p.getLong("watchedAtMs")))
            return
        }
        val map = mapping(); var uid = map.optLong(id, -1)
        if (remove) { if (uid > 0) db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE uid=?", arrayOf(uid)); return }
        val name = if (id == "watch-later") "Watch Later (synced)" else p.getString("name")
        if (uid < 1 || !exists(uid)) { uid = db.playlistDAO().insert(PlaylistEntity(name, "", 0)); map.put(id, uid); save(map) }
        else if (id != "watch-later") db.openHelper.writableDatabase.execSQL("UPDATE playlists SET name=? WHERE uid=?", arrayOf(name, uid))
        db.playlistStreamDAO().deleteBatch(uid)
        val items = p.getJSONArray("items")
        for (i in 0 until items.length()) db.playlistStreamDAO().insert(PlaylistStreamEntity(uid, stream(items.getString(i)), i))
    }
}
