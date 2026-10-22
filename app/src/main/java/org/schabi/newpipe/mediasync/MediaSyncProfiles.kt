package org.schabi.newpipe.mediasync

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.mediasync.MediaSyncCrypto.check
import java.util.UUID
import java.util.Locale

/** Real profile/feed-group adapter. History remains global. Rows 3/4 survive disconnect.
 * Metadata and each membership have independent revisions; names never identify profiles.
 * Deferred members survive pagination/restart without manufacturing global subscriptions.
 * Calls run within the sync client's Room transaction, including mapping and membership.
 */
class MediaSyncProfiles(private val db: AppDatabase) {
    private fun read(row: Int): JSONObject = db.query("SELECT body FROM dd_media_sync WHERE id=?", arrayOf(row)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
    }
    private fun save(row: Int, map: JSONObject) = db.mediaSyncStateDAO().write(MediaSyncState(row, map.toString()))
    private fun exists(uid: Long) = db.query("SELECT uid FROM feed_group WHERE uid=?", arrayOf(uid)).use { it.moveToFirst() }
    private fun subscription(channel: String): Long = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/channel/$channel")).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    fun snapshot(): List<JSONObject> {
        val map = read(3); val deferred = read(4); val result = mutableListOf<JSONObject>()
        db.query("SELECT uid,name,bg_color,text_color FROM feed_group ORDER BY uid LIMIT 5001", null).use { groups ->
            while (groups.moveToNext()) {
                check(result.size < 5000, "library_limit")
                val uid = groups.getLong(0)
                val id = map.keys().asSequence().firstOrNull { map.optLong(it, -1) == uid }
                    ?: UUID.randomUUID().toString().also { map.put(it, uid); save(3, map) }
                result.add(MediaSyncCrypto.validatePayload(JSONObject().put("kind", "profile").put("provider", "youtube")
                    .put("version", 3).put("id", id).put("name", groups.getString(1))
                    .put("bgColor", groups.getString(2).uppercase(Locale.ROOT)).put("textColor", groups.getString(3).uppercase(Locale.ROOT))))
                val channels = mutableSetOf<String>()
                db.query("SELECT s.url FROM subscriptions s JOIN feed_group_subscription_join j ON j.subscription_id=s.uid WHERE j.group_id=? AND s.service_id=0", arrayOf(uid)).use { subscriptions ->
                    while (subscriptions.moveToNext()) {
                        val channel = Regex("https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})").matchEntire(subscriptions.getString(0))?.groupValues?.get(1)
                        // Alias/noncanonical identities remain local, like the global v1 adapter.
                        if (channel != null) channels.add(channel)
                    }
                }
                val missing = deferred.optJSONArray(id) ?: JSONArray()
                val retained = JSONArray()
                for (i in 0 until missing.length()) {
                    val channel = missing.getString(i); val subUid = subscription(channel)
                    if (subUid > 0) db.feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(uid, subUid)))
                    else retained.put(channel)
                    channels.add(channel)
                }
                deferred.put(id, retained)
                for (channel in channels.sorted()) {
                    check(result.size < 5000, "library_limit")
                    result.add(MediaSyncCrypto.validatePayload(JSONObject().put("kind", "profile-member").put("provider", "youtube")
                        .put("version", 3).put("id", "$id:$channel").put("profileId", id).put("channelId", channel)))
                }
            }
        }
        save(4, deferred)
        return result
    }
    /** Called for global unsubscribe, including locally captured deletion. Never resurrect it. */
    fun removeChannel(channel: String) {
        val deferred = read(4)
        for (id in deferred.keys().asSequence().toList()) {
            val items = deferred.getJSONArray(id)
            deferred.put(id, JSONArray((0 until items.length()).map { items.getString(it) }.filter { it != channel }))
        }
        save(4, deferred)
    }
    fun project(record: JSONObject, explicitlyUnsubscribed: (String) -> Boolean = { false }, profileDeleted: (String) -> Boolean = { false }) {
        val p = MediaSyncCrypto.validatePayload(record.getJSONObject("payload"))
        val member = p.getString("kind") == "profile-member"
        check(member || p.getString("kind") == "profile")
        val id = if (member) p.getString("profileId") else p.getString("id")
        val map = read(3); val deferred = read(4); var uid = map.optLong(id, -1)
        val remove = record.getString("operation") == "remove"
        if (!member && remove) {
            if (uid > 0) db.feedGroupDAO().delete(uid)
            deferred.remove(id); save(4, deferred)
            return
        }
        if (member && (profileDeleted(id) || explicitlyUnsubscribed(p.getString("channelId"))) && !remove) return
        if (uid < 1 || !exists(uid)) {
            if (remove) return
            uid = db.feedGroupDAO().insert(FeedGroupEntity(0, if (member) "Synced profile" else p.getString("name"), FeedGroupIcon.PERSON))
            map.put(id, uid); save(3, map)
        }
        if (!member) {
            db.openHelper.writableDatabase.execSQL("UPDATE feed_group SET name=?,bg_color=?,text_color=? WHERE uid=?", arrayOf(p.getString("name"), p.getString("bgColor").uppercase(Locale.ROOT), p.getString("textColor").uppercase(Locale.ROOT), uid))
            return
        }
        val channel = p.getString("channelId"); val subscriptionUid = subscription(channel)
        val missing = deferred.optJSONArray(id) ?: JSONArray()
        val channels = (0 until missing.length()).map { missing.getString(it) }.toMutableSet()
        channels.remove(channel)
        if (remove) {
            if (subscriptionUid > 0) db.openHelper.writableDatabase.execSQL("DELETE FROM feed_group_subscription_join WHERE group_id=? AND subscription_id=?", arrayOf(uid, subscriptionUid))
        } else if (subscriptionUid > 0) db.feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(uid, subscriptionUid)))
        else channels.add(channel)
        deferred.put(id, JSONArray(channels.sorted())); save(4, deferred)
    }
}
