package org.schabi.newpipe.mediasync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.mediasync.MediaSyncCrypto.check
import org.schabi.newpipe.mediasync.MediaSyncCrypto.number

/** Real Room adapter. No network calls inside transactions, no timers and no player commands. */
class MediaSyncClient(private val db: AppDatabase, config: JSONObject) {
    private val config = MediaSyncCrypto.validateConfig(config)
    private val credential = config.getJSONObject("credential")
    private val keys = config.getJSONObject("keys")
    private val endpoint = config.getString("endpoint").trimEnd('/')
    private val owner = credential.getString("ownerId")
    private var state: JSONObject
    private var syncBase: JSONObject? = null
    init {
        val saved = db.mediaSyncStateDAO().read()
        state = if (saved == null) JSONObject().put("protocol", 1).put("ownerId", owner).put("deviceId", credential.getString("deviceId"))
            .put("keyFingerprint", MediaSyncCrypto.fingerprint(keys)).put("generation", credential.getString("serverGeneration"))
            .put("cursor", 0).put("epoch", 0).put("remote", JSONObject()).put("local", JSONObject()).put("baseline", JSONObject())
            .put("pending", JSONArray()).put("conflicts", JSONObject()).put("blocked", false).put("sessionId", UUID.randomUUID().toString()).put("checkpointSeq", 0)
        else JSONObject(saved)
        check(state.getInt("protocol") == 1 && state.getString("ownerId") == owner && state.getString("deviceId") == credential.getString("deviceId") && state.getString("keyFingerprint") == MediaSyncCrypto.fingerprint(keys), "client_state_mismatch")
        atomic { }
    }
    private fun atomic(block: () -> Unit) {
        val previous = state.toString()
        try { db.runInTransaction { block(); db.mediaSyncStateDAO().write(MediaSyncState(body = state.toString())) } }
        catch (e: Exception) { state = JSONObject(previous); throw e }
    }
    private fun map(name: String) = state.getJSONObject(name)
    private fun pending() = state.getJSONArray("pending")
    private fun list(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun key(p: JSONObject) = MediaSyncCrypto.recordKey(keys, p)
    private fun same(a: JSONObject?, b: JSONObject?) = a?.optString("kind") == b?.optString("kind") && a?.optString("id") == b?.optString("id") && a?.optLong("positionMs", -1) == b?.optLong("positionMs", -1)
    private fun snapshot(): JSONObject {
        val result = JSONObject()
        db.query("SELECT url FROM subscriptions WHERE service_id=0 LIMIT 5001", null).use { c ->
            var count = 0
            while (c.moveToNext()) {
                check(++count <= 5000, "library_limit")
                val id = Regex("https://www\\.youtube\\.com/channel/(UC[A-Za-z0-9_-]{22})").matchEntire(c.getString(0))?.groupValues?.get(1) ?: continue
                val p = JSONObject().put("kind", "subscription").put("provider", "youtube").put("id", id); result.put(key(p), p)
            }
        }
        db.query("SELECT s.url,p.progress_time FROM streams s JOIN stream_state p ON s.uid=p.stream_id WHERE s.service_id=0 LIMIT 5001", null).use { c ->
            var count = 0
            while (c.moveToNext()) {
                check(++count <= 5000, "library_limit")
                val id = Regex("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})").matchEntire(c.getString(0))?.groupValues?.get(1) ?: continue
                val position = c.getLong(1); if (position !in 0..31536000000L) continue
                val p = JSONObject().put("kind", "resume").put("provider", "youtube").put("id", id).put("positionMs", position).put("sessionId", state.getString("sessionId")).put("checkpointSeq", number(state, "checkpointSeq"))
                result.put(key(p), p)
            }
        }
        return result
    }
    private fun enqueue(p: JSONObject, operation: String, base: Long? = null) {
        check(pending().length() < 1000, "outbox_full")
        val k = key(p); val predecessor = list(pending()).lastOrNull { key(it.getJSONObject("payload")) == k }?.getString("intentId")
        val epoch = if (p.getString("kind") == "resume") number(state, "epoch") else 0L
        map("local").put(k, JSONObject().put("payload", p).put("operation", operation).put("epoch", epoch))
        pending().put(JSONObject().put("intentId", UUID.randomUUID().toString()).put("payload", p).put("operation", operation).put("epoch", epoch)
            .put("baseRevision", base ?: map("remote").optJSONObject(k)?.optLong("revision", 0) ?: 0L).put("predecessor", predecessor ?: JSONObject.NULL).put("wire", JSONObject.NULL))
    }
    private fun capture() {
        val current = snapshot(); val old = map("baseline")
        for (k in (current.keys().asSequence().toSet() + old.keys().asSequence().toSet())) {
            val p = current.optJSONObject(k); val before = old.optJSONObject(k)
            if (!same(p, before)) {
                val value = p ?: before!!
                if (value.getString("kind") == "resume") { state.put("checkpointSeq", number(state, "checkpointSeq") + 1); value.put("checkpointSeq", number(state, "checkpointSeq")) }
                enqueue(value, if (p == null) "remove" else "put", syncBase?.optJSONObject(k)?.optLong("revision", 0) ?: if (syncBase != null) 0L else null)
            }
        }
        state.put("baseline", current)
    }
    private fun request(path: String, mutation: JSONObject? = null): Pair<Int, JSONObject> {
        val builder = Request.Builder().url(endpoint + path).header("Authorization", "Bearer ${credential.getString("deviceId")}.${credential.getString("token")}").header("x-dd-sync-version", "1")
        if (mutation != null) builder.post(mutation.toString().toRequestBody("application/json".toMediaType()))
        val result = http.newCall(builder.build()).execute().use { response ->
            check(response.code !in 300..399, "redirect_rejected")
            val bytes = ByteArrayOutputStream(); val input = response.body?.byteStream() ?: throw SyncFailure("invalid_response")
            val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(bytes.size() + n <= 1048576, "response_too_large"); bytes.write(buffer, 0, n) }
            response.code to JSONObject(bytes.toString("UTF-8"))
        }
        check(result.second.get("protocol") == 1, "unsupported_protocol")
        if (result.second.optString("code") == "server_generation_changed") { atomic { state.put("blocked", true) }; throw SyncFailure("restored_service") }
        return result
    }
    private fun flush(): Boolean {
        while (pending().length() > 0) {
            val entry = pending().getJSONObject(0); val p = entry.getJSONObject("payload"); val k = key(p)
            if (map("conflicts").has(k)) return true
            if (entry.isNull("wire")) atomic {
                val header = JSONObject().put("serverGeneration", state.getString("generation")).put("mutationId", entry.getString("intentId")).put("operation", entry.getString("operation"))
                    .put("baseRevision", number(entry, "baseRevision")).put("resumeEpoch", number(entry, "epoch"))
                entry.put("wire", MediaSyncCrypto.seal(keys, owner, header, p))
            }
            val (status, r) = try { request("/v1/mutations", entry.getJSONObject("wire")) } catch (_: IOException) { return false }
            if (status == 409) { atomic { map("conflicts").put(k, JSONObject().put("revision", r.optLong("revision", -1)).put("code", r.optString("code"))) }; return true }
            check(status == 200 && r.getString("serverGeneration") == state.getString("generation"), if (status == 401) "unauthorized" else "sync_rejected")
            val revision = number(r, "revision")
            atomic {
                map("remote").put(k, JSONObject().put("payload", p).put("operation", entry.getString("operation")).put("epoch", number(entry, "epoch")).put("revision", revision))
                val remaining = JSONArray(); for (next in list(pending()).drop(1)) {
                    if (next.optString("predecessor") == entry.getString("intentId")) next.put("baseRevision", revision).put("predecessor", JSONObject.NULL)
                    remaining.put(next)
                }; state.put("pending", remaining)
            }
        }
        return true
    }
    private fun project(record: JSONObject) {
        val p = record.getJSONObject("payload"); val id = p.getString("id"); val remove = record.getString("operation") == "remove"
        if (p.getString("kind") == "subscription") {
            val url = "https://www.youtube.com/channel/$id"
            if (remove) db.subscriptionDAO().deleteSubscription(0, url)
            else {
                val exists = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf(url)).use { it.moveToFirst() }
                if (!exists) { val row = SubscriptionEntity(); row.serviceId = 0; row.url = url; row.name = id; row.avatarUrl = ""; row.notificationMode = 0; db.subscriptionDAO().insert(row) }
            }
        } else {
            val url = "https://www.youtube.com/watch?v=$id"
            var uid = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            if (remove) { if (uid > 0) db.streamStateDAO().deleteState(uid) }
            else {
                if (uid < 0) uid = db.streamDAO().insert(StreamEntity(serviceId = 0, url = url, title = "Synced resume: $id", streamType = StreamType.VIDEO_STREAM, duration = -1, uploader = ""))
                db.streamStateDAO().upsert(StreamStateEntity(uid, number(p, "positionMs")))
            }
        }
    }
    private fun pull() {
        var watermark: Long? = null
        repeat(10001) {
            val path = "/v1/changes?generation=${state.getString("generation")}&after=${number(state, "cursor")}&limit=100" + (watermark?.let { "&until=$it" } ?: "")
            val (status, r) = request(path); check(status == 200, if (status == 401) "unauthorized" else "pull_rejected")
            val cursor = number(r, "cursor"); val high = number(r, "watermark"); val epoch = number(r, "resumeEpoch")
            check(r.getString("serverGeneration") == state.getString("generation") && cursor >= number(state, "cursor") && cursor <= high && (watermark == null || watermark == high) && epoch >= number(state, "epoch"))
            val changes = r.getJSONArray("changes"); check(changes.length() <= 100)
            var last = number(state, "cursor")
            val records = mutableListOf<Pair<Long, JSONObject>>()
            for (change in list(changes)) {
                val seq = number(change, "sequence"); check(seq > last && seq <= cursor); last = seq
                if (change.getString("kind") == "reset" && change.isNull("mutation")) continue
                val m = change.getJSONObject("mutation"); check(change.getString("kind") == m.getString("kind"))
                val p = MediaSyncCrypto.open(keys, owner, m)
                records.add(seq to JSONObject().put("payload", p).put("operation", m.getString("operation")).put("epoch", number(m, "resumeEpoch")).put("revision", seq))
            }
            check(if (changes.length() > 0) cursor == last else cursor == high)
            atomic {
                capture() // Catch edits made while HTTP was in flight, before acknowledging remote revisions.
                if (epoch > number(state, "epoch")) {
                    val old = map("baseline")
                    for (k in old.keys().asSequence().toList()) if (old.getJSONObject(k).getString("kind") == "resume") project(JSONObject().put("payload", old.getJSONObject(k)).put("operation", "remove"))
                    state.put("pending", JSONArray(list(pending()).filter { it.getJSONObject("payload").getString("kind") != "resume" || number(it, "epoch") >= epoch }))
                    for (name in listOf("local", "remote")) for (k in map(name).keys().asSequence().toList()) if (map(name).getJSONObject(k).getJSONObject("payload").getString("kind") == "resume") { map(name).remove(k); map("conflicts").remove(k) }
                    state.put("epoch", epoch)
                }
                for ((seq, record) in records) {
                    val p = record.getJSONObject("payload"); val k = key(p)
                    if (p.getString("kind") == "resume" && number(record, "epoch") < epoch) continue
                    if ((map("remote").optJSONObject(k)?.optLong("revision", 0) ?: 0) >= seq) continue
                    map("remote").put(k, record)
                    if (list(pending()).none { key(it.getJSONObject("payload")) == k }) { project(record); map("local").put(k, record) }
                }
                state.put("baseline", snapshot()).put("cursor", cursor)
            }
            watermark = high; if (cursor == high) return
        }
        throw SyncFailure("page_limit")
    }
    @Synchronized fun sync(choice: String? = null): JSONObject {
        check(!state.getBoolean("blocked"), "restored_service")
        check(choice == null || choice in listOf("keep-local", "use-remote"))
        syncBase = JSONObject(map("remote").toString())
        try {
            atomic { capture() }; if (!flush()) return status().put("code", "offline")
            pull()
            if (choice != null) {
                atomic {
                    for (k in map("conflicts").keys().asSequence().toList()) {
                        val remote = map("remote").optJSONObject(k) ?: throw SyncFailure("pull_before_resolution")
                        check(number(remote, "revision") >= map("conflicts").getJSONObject(k).getLong("revision"), "pull_before_resolution")
                        val local = map("local").getJSONObject(k)
                        state.put("pending", JSONArray(list(pending()).filter { key(it.getJSONObject("payload")) != k })); map("conflicts").remove(k)
                        if (choice == "use-remote") { project(remote); map("local").put(k, remote) }
                        else enqueue(local.getJSONObject("payload"), local.getString("operation"), number(remote, "revision"))
                    }; state.put("baseline", snapshot())
                }
                if (!flush()) return status().put("code", "offline")
            }
            atomic { state.put("lastSyncComplete", true) }; return status()
        } catch (_: IOException) { return status().put("code", "offline") } finally { syncBase = null }
    }
    fun status(): JSONObject = JSONObject().put("enabled", true).put("pending", pending().length()).put("conflicts", map("conflicts").length())
        .put("code", if (state.getBoolean("blocked")) "restored_service" else if (map("conflicts").length() > 0) "conflict" else if (pending().length() > 0) "pending" else if (state.optBoolean("lastSyncComplete")) "synced" else "ready")
    companion object {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
}
