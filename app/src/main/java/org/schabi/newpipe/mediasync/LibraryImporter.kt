package org.schabi.newpipe.mediasync

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Preview is read-only; commit and its import identity mappings use one Room transaction. */
class LibraryImporter(private val db: AppDatabase) {
    private fun mapping(row: Int) = db.query("SELECT body FROM dd_media_sync WHERE id=?", arrayOf(row)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() }
    fun snapshot(opaque: MutableSet<String> = mutableSetOf()): List<JSONObject> {
        val result = mutableListOf<JSONObject>(); val groups = mapping(3); val lists = mapping(2)
        fun portable(p: JSONObject): Boolean = try { MediaSyncCrypto.validatePayload(p); result.add(p); true } catch (_: Exception) { opaque.add(LibraryImportRules.key(p)); false }
        fun read(sql: String, action: (android.database.Cursor) -> Unit) { db.query(sql + " LIMIT 5001", null).use { var n = 0; while (it.moveToNext()) { require(++n <= 5000) { "import_collection_limit" }; action(it) } } }
        read("SELECT url FROM subscriptions WHERE service_id=0") { c -> LibraryImportRules.canonical(c.getString(0), true)?.let { result.add(LibraryImportRules.payload("subscription", it)) } }
        read("SELECT uid,name,bg_color,text_color FROM feed_group") { c ->
            val uid = c.getLong(0); val id = groups.keys().asSequence().firstOrNull { groups.optLong(it, -1) == uid } ?: LibraryImportRules.id("pipepipe-existing-profile", uid.toString())
            if (!portable(LibraryImportRules.payload("profile", id).put("name", c.getString(1)).put("bgColor", c.getString(2).uppercase(java.util.Locale.ROOT)).put("textColor", c.getString(3).uppercase(java.util.Locale.ROOT)))) return@read
            db.query("SELECT s.url FROM subscriptions s JOIN feed_group_subscription_join j ON j.subscription_id=s.uid WHERE j.group_id=? AND s.service_id=0 LIMIT 5001", arrayOf(uid)).use { members ->
                while (members.moveToNext()) { val channel = LibraryImportRules.canonical(members.getString(0), true) ?: continue; result.add(LibraryImportRules.payload("profile-member", "$id:$channel").put("profileId", id).put("channelId", channel)); require(result.size <= 5000) { "import_record_limit" } }
            }
        }
        read("SELECT uid,name FROM playlists") { c ->
            val uid = c.getLong(0); val watch = lists.optLong("watch-later", -1) == uid; val id = if (watch) "watch-later" else lists.keys().asSequence().firstOrNull { it != "watch-later" && lists.optLong(it, -1) == uid } ?: LibraryImportRules.id("pipepipe-existing-playlist", uid.toString())
            val items = JSONArray(); var supported = true
            db.query("SELECT s.service_id,s.url FROM playlist_stream_join j JOIN streams s ON s.uid=j.stream_id WHERE j.playlist_id=? ORDER BY j.join_index LIMIT 201", arrayOf(uid)).use { videos -> while (videos.moveToNext()) { val video = if (videos.getInt(0) == 0) LibraryImportRules.canonical(videos.getString(1), false) else null; if (video == null) supported = false else items.put(video) } }
            val p = LibraryImportRules.payload(if (watch) "watch-later" else "playlist", id).put("items", items); if (!watch) p.put("name", c.getString(1)); if (supported) portable(p) else opaque.add(LibraryImportRules.key(p))
        }
        read("SELECT s.url,MAX(h.access_date) FROM streams s JOIN stream_history h ON h.stream_id=s.uid WHERE s.service_id=0 GROUP BY s.uid") { c -> val id = LibraryImportRules.canonical(c.getString(0), false); if (id != null && c.getLong(1) > 0) result.add(LibraryImportRules.payload("history", id).put("watchedAtMs", c.getLong(1))) }
        read("SELECT s.url,p.progress_time FROM streams s JOIN stream_state p ON p.stream_id=s.uid WHERE s.service_id=0") { c -> val id = LibraryImportRules.canonical(c.getString(0), false); if (id != null) result.add(LibraryImportRules.payload("resume", id).put("positionMs", c.getLong(1)).put("sessionId", LibraryImportRules.id("pipepipe-existing-resume", id)).put("checkpointSeq", 0)) }
        return result
    }
    private fun plan(records: List<JSONObject>): List<JSONObject> {
        val opaque = mutableSetOf<String>(); val before = snapshot(opaque)
        require(records.none { LibraryImportRules.key(it) in opaque || (it.optString("kind") == "profile-member" && "profile:" + it.getString("profileId") in opaque) }) { "existing_collection_not_portable" }
        return LibraryImportRules.merge(before, records)
    }
    fun preview(records: List<JSONObject>): List<JSONObject> = db.runInTransaction(java.util.concurrent.Callable { plan(records) })
    fun commit(records: List<JSONObject>): List<JSONObject> = db.runInTransaction(java.util.concurrent.Callable {
        val changes = plan(records)
        val clientState = db.mediaSyncStateDAO().read()?.let { JSONObject(it) }
        if (clientState != null) require((clientState.optJSONArray("pending")?.length() ?: 0) + changes.size <= 1000) { "outbox_full" }
        val order = mapOf("subscription" to 0, "profile" to 1, "profile-member" to 2, "playlist" to 3, "watch-later" to 3, "history" to 4, "resume" to 5)
        val profiles = MediaSyncProfiles(db); val library = MediaSyncLibrary(db)
        for (p in changes.sortedBy { order[it.getString("kind")] ?: 10 }) {
            when (p.getString("kind")) {
                "subscription" -> {
                    val url = "https://www.youtube.com/channel/" + p.getString("id")
                    val found = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf(url)).use { it.moveToFirst() }
                    if (!found) db.subscriptionDAO().insert(SubscriptionEntity().apply { serviceId = 0; this.url = url; name = p.getString("id"); avatarUrl = ""; notificationMode = 0 })
                }
                "profile", "profile-member" -> profiles.project(JSONObject().put("payload", p).put("operation", "put"))
                "resume" -> {
                    val id = p.getString("id"); val url = "https://www.youtube.com/watch?v=$id"
                    var uid = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
                    if (uid < 1) uid = db.streamDAO().insert(StreamEntity(serviceId = 0, url = url, title = "Imported video: $id", streamType = StreamType.VIDEO_STREAM, duration = -1, uploader = ""))
                    val hasProgress = db.query("SELECT stream_id FROM stream_state WHERE stream_id=?", arrayOf(uid)).use { it.moveToFirst() }
                    if (!hasProgress) db.streamStateDAO().upsert(StreamStateEntity(uid, p.getLong("positionMs")))
                }
                else -> library.project(JSONObject().put("payload", p).put("operation", "put"))
            }
        }
        changes
    })
}
