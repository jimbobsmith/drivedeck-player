package org.schabi.newpipe.mediasync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Pure export normalization and additive merge. No network, Android state, secrets or settings. */
object LibraryImportRules {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_RECORDS = 5000
    data class Parsed(val source: String, val records: List<JSONObject>, val unsupported: Int = 0, val remotePlaylists: Int = 0)
    fun id(namespace: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((namespace + "\u0000" + value).toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 15) or 64).toByte(); bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
        val buffer = ByteBuffer.wrap(bytes); return UUID(buffer.long, buffer.long).toString()
    }
    fun canonical(value: String?, channel: Boolean): String? {
        val pattern = if (channel) Regex("UC[A-Za-z0-9_-]{22}") else Regex("[A-Za-z0-9_-]{11}")
        if (value == null) return null
        if (pattern.matches(value)) return value
        return try {
            val u = URI(value)
            if (u.scheme != "https" || u.userInfo != null || u.port != -1 || u.fragment != null) return null
            val path = u.rawPath
            val extracted = when {
                !channel && u.host == "youtu.be" && u.rawQuery == null -> path.removePrefix("/")
                u.host in listOf("youtube.com", "www.youtube.com", "m.youtube.com") && channel && u.rawQuery == null -> Regex("/channel/([^/]+)/?").matchEntire(path)?.groupValues?.get(1)
                u.host in listOf("youtube.com", "www.youtube.com", "m.youtube.com") && !channel && path == "/watch" -> Regex("v=([A-Za-z0-9_-]{11})").matchEntire(u.rawQuery ?: "")?.groupValues?.get(1)
                u.host in listOf("youtube.com", "www.youtube.com", "m.youtube.com") && !channel && u.rawQuery == null -> Regex("/(?:shorts|live|embed)/([^/]+)/?").matchEntire(path)?.groupValues?.get(1)
                else -> null
            }
            extracted?.takeIf { pattern.matches(it) }
        } catch (_: Exception) { null }
    }
    fun payload(kind: String, id: String): JSONObject = JSONObject().put("kind", kind).put("provider", "youtube").put("id", id).apply {
        if (kind in listOf("profile", "profile-member")) put("version", 3)
        else if (kind in listOf("playlist", "watch-later", "history")) put("version", 2)
    }
    fun objects(array: JSONArray): List<JSONObject> { require(array.length() <= MAX_RECORDS) { "import_collection_limit" }; return (0 until array.length()).map { array.getJSONObject(it) } }
    fun key(p: JSONObject) = p.getString("kind") + ":" + p.getString("id")
    private fun color(value: String, fallback: String) = if (Regex("#[a-fA-F0-9]{6}").matches(value)) value.uppercase(java.util.Locale.ROOT) else fallback
    fun plainTable(sql: String?): Boolean = sql != null && Regex("""(?is)^CREATE\s+TABLE\b.*""").matches(sql) && !Regex("""(?i)\bGENERATED\b|\bAS\s*\(""").containsMatchIn(sql)
    fun append(records: MutableList<JSONObject>, index: MutableMap<String, Int>, p: JSONObject) {
        MediaSyncCrypto.validatePayload(p)
        val at = index[key(p)]
        if (at != null) {
            val old = records[at]
            if (p.getString("kind") in listOf("profile", "playlist")) require(same(old, p)) { "duplicate_import_identity" }
            if (p.getString("kind") == "history" && old.getLong("watchedAtMs") > p.getLong("watchedAtMs")) return
            records[at] = p
        } else { index[key(p)] = records.size; records.add(p); require(records.size <= MAX_RECORDS) { "import_record_limit" } }
    }
    fun parse(text: String): Parsed {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "import_file_limit" }
        val clean = text.removePrefix("\uFEFF").trim(); if (clean.isEmpty()) return Parsed("Empty export", emptyList())
        val rows: List<JSONObject>
        var pipe: JSONObject? = null
        if (clean.startsWith("[")) rows = objects(JSONArray(clean))
        else {
            val single = try { val token = org.json.JSONTokener(clean); val value = token.nextValue(); if (token.nextClean() == '\u0000') value as? JSONObject else null } catch (_: Exception) { null }
            if (single != null && single.has("subscriptions") && !single.has("_id")) pipe = single
            rows = if (single != null) listOf(single) else clean.lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        }
        if (rows.isEmpty()) return Parsed("Empty export", emptyList())
        require(rows.size in 1..MAX_RECORDS) { "import_collection_limit" }
        val records = mutableListOf<JSONObject>(); var unsupported = 0
        val index = mutableMapOf<String, Int>()
        fun add(p: JSONObject) = append(records, index, p)
        fun subscribe(raw: String): String? { val channel = canonical(raw, true); if (channel == null) unsupported++ else add(payload("subscription", channel)); return channel }
        val source: String
        if (pipe != null) {
            source = "PipePipe / NewPipe subscriptions JSON"
            for (row in objects(pipe.getJSONArray("subscriptions"))) if (row.opt("service_id") is Number && row.optInt("service_id", -1) == 0) subscribe(row.optString("url")) else unsupported++
        } else if (rows.all { it.has("subscriptions") }) {
            source = "FreeTube subscriptions and categories"
            for (row in rows) {
                val sourceId = row.getString("_id"); require(sourceId.length in 1..256) { "invalid_import_identity" }
                val group = id("freetube-profile", sourceId)
                if (sourceId != "allChannels") add(payload("profile", group).put("name", row.getString("name")).put("bgColor", color(row.optString("bgColor"), "#333333")).put("textColor", color(row.optString("textColor"), "#FFFFFF")))
                for (item in objects(row.getJSONArray("subscriptions"))) {
                    val channel = subscribe(item.optString("id"))
                    if (channel != null && sourceId != "allChannels") add(payload("profile-member", "$group:$channel").put("profileId", group).put("channelId", channel))
                }
            }
        } else if (rows.all { it.has("videos") && it.has("playlistName") }) {
            source = "FreeTube playlists and Watch Later"
            for (row in rows) {
                val sourceId = row.getString("_id"); require(sourceId.length in 1..256) { "invalid_import_identity" }
                val items = JSONArray()
                for (item in objects(row.getJSONArray("videos"))) { val video = canonical(item.optString("videoId"), false); if (video == null) unsupported++ else items.put(video) }
                val watch = sourceId == "watchLater"
                val p = payload(if (watch) "watch-later" else "playlist", if (watch) "watch-later" else id("freetube-playlist", sourceId)).put("items", items)
                if (!watch) p.put("name", row.getString("playlistName")); add(p)
            }
        } else if (rows.all { it.has("videoId") }) {
            source = "FreeTube watch history and progress"
            for (row in rows) {
                val video = canonical(row.optString("videoId"), false); if (video == null) { unsupported++; continue }
                if (row.opt("timeWatched") is Number && row.optLong("timeWatched") > 0) add(payload("history", video).put("watchedAtMs", row.getLong("timeWatched")))
                if (row.opt("watchProgress") is Number) add(payload("resume", video).put("positionMs", Math.round(row.getDouble("watchProgress") * 1000)).put("sessionId", id("freetube-resume", video)).put("checkpointSeq", 0))
            }
        } else throw IllegalArgumentException("unsupported_import_format")
        return Parsed(source, deduplicate(records), unsupported)
    }
    fun deduplicate(records: List<JSONObject>): List<JSONObject> {
        val map = linkedMapOf<String, JSONObject>()
        for (p in records) {
            MediaSyncCrypto.validatePayload(p); val old = map[key(p)]
            if (old != null && p.getString("kind") == "history" && old.getLong("watchedAtMs") > p.getLong("watchedAtMs")) continue
            if (old != null && p.getString("kind") in listOf("profile", "playlist")) require(same(old, p)) { "duplicate_import_identity" }
            map[key(p)] = p
        }
        require(map.size <= MAX_RECORDS) { "import_record_limit" }; return map.values.toList()
    }
    fun same(a: JSONObject, b: JSONObject): Boolean = canonicalJson(a) == canonicalJson(b)
    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalJson(value.get(it)) }
        else -> value.toString()
    }
    fun merge(existing: List<JSONObject>, imported: List<JSONObject>): List<JSONObject> {
        val map = existing.associateBy { key(it) }.toMutableMap(); val changes = mutableListOf<JSONObject>()
        for (p in imported) {
            MediaSyncCrypto.validatePayload(p); val old = map[key(p)]
            val next = when {
                old == null -> p
                p.getString("kind") == "history" -> if (old.getLong("watchedAtMs") >= p.getLong("watchedAtMs")) old else p
                p.has("items") -> {
                    val before = old.getJSONArray("items"); val result = (0 until before.length()).map { before.getString(it) }.toMutableList(); val counts = result.groupingBy { it }.eachCount().toMutableMap(); val incoming = p.getJSONArray("items")
                    for (i in 0 until incoming.length()) { val id = incoming.getString(i); val n = counts[id] ?: 0; if (n > 0) counts[id] = n - 1 else result.add(id) }
                    JSONObject(old.toString()).put("items", JSONArray(result)).also { MediaSyncCrypto.validatePayload(it) }
                }
                else -> old
            }
            if (old == null || !same(old, next)) { changes.add(next); map[key(next)] = next }
        }
        require(map.size <= MAX_RECORDS && changes.size <= 1000) { "import_change_limit" }; return changes
    }
    fun counts(records: List<JSONObject>): String = records.groupingBy { it.getString("kind") }.eachCount().entries.joinToString("\n") { it.key + ": " + it.value }
}
