package org.schabi.newpipe.mediasync

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Reads only portable table data; never restores a DB, runs supplied SQL or deserializes settings. */
object LibraryBackupReader {
    fun bounded(input: InputStream, limit: Int = LibraryImportRules.MAX_BYTES): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "import_file_limit" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    fun parse(input: InputStream, cache: File): LibraryImportRules.Parsed {
        val bytes = bounded(input)
        if (bytes.size < 2 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return LibraryImportRules.parse(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        val zipFile = File.createTempFile("dd-import-", ".zip", cache); val source = File.createTempFile("dd-import-", ".sqlite", cache)
        try {
            zipFile.writeBytes(bytes)
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList(); require(entries.size in 1..16) { "invalid_import_archive" }
                require(entries.map { it.name }.distinct().size == entries.size && entries.all { !it.name.contains('/') && !it.name.contains('\\') && !it.name.contains('\u0000') && it.size in 0..LibraryImportRules.MAX_BYTES.toLong() }) { "invalid_import_archive" }
                require(entries.sumOf { it.size } <= LibraryImportRules.MAX_BYTES.toLong() * 2) { "import_archive_limit" }
                val entry = zip.getEntry("newpipe.db") ?: throw IllegalArgumentException("pipepipe_database_missing")
                val data = zip.getInputStream(entry).use { bounded(it) }
                require(data.size.toLong() == entry.size && CRC32().apply { update(data) }.value == entry.crc && String(data.take(15).toByteArray(), Charsets.US_ASCII) == "SQLite format 3") { "invalid_import_database" }
                source.writeBytes(data)
            }
            return read(source)
        } finally { source.delete(); zipFile.delete() }
    }
    private fun read(source: File): LibraryImportRules.Parsed {
        val db = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            require(db.version in 1..903) { "unsupported_import_database_version" }
            db.execSQL("PRAGMA query_only=ON"); db.execSQL("PRAGMA trusted_schema=OFF")
            val tables = mutableMapOf<String, List<JSONObject>>()
            val present = mutableSetOf<String>()
            val columns = mapOf("subscriptions" to listOf("uid","service_id","url"), "feed_group" to listOf("uid","name"), "feed_group_subscription_join" to listOf("group_id","subscription_id"), "streams" to listOf("uid","service_id","url"), "stream_history" to listOf("stream_id","access_date"), "stream_state" to listOf("stream_id","progress_time"), "playlists" to listOf("uid","name"), "playlist_stream_join" to listOf("playlist_id","stream_id","join_index"), "remote_playlists" to listOf("uid"))
            val names = listOf("subscriptions", "feed_group", "feed_group_subscription_join", "streams", "stream_history", "stream_state", "playlists", "playlist_stream_join", "remote_playlists")
            for (name in names) {
                val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }
                if (sql == null) { tables[name] = emptyList(); continue }
                present.add(name)
                require(LibraryImportRules.plainTable(sql)) { "invalid_import_database_schema" }
                val available = db.rawQuery("PRAGMA table_info(\"$name\")", null).use { c -> val values = mutableSetOf<String>(); while (c.moveToNext()) values.add(c.getString(c.getColumnIndexOrThrow("name"))); values }
                require(columns.getValue(name).all { it in available }) { "invalid_import_database_schema" }
                val selected = columns.getValue(name) + if (name == "feed_group") listOf("bg_color", "text_color").filter { it in available } else emptyList()
                val projection = if (name == "stream_history") "stream_id,MAX(access_date) AS access_date" else selected.joinToString(",") { "\"$it\"" }
                tables[name] = db.rawQuery("SELECT $projection FROM \"$name\"" + (if (name == "stream_history") " GROUP BY stream_id" else "") + " LIMIT 5001", null).use { c ->
                    val rows = mutableListOf<JSONObject>()
                    while (c.moveToNext()) { require(rows.size < 5000) { "import_collection_limit" }; val row = JSONObject(); for (i in 0 until c.columnCount) when (c.getType(i)) { android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i)); android.database.Cursor.FIELD_TYPE_STRING -> row.put(c.getColumnName(i), c.getString(i)); android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(c.getColumnName(i), c.getDouble(i)) }; rows.add(row) }; rows
                }
            }
            require(present.containsAll(listOf("subscriptions", "streams", "playlists"))) { "invalid_import_database_schema" }
            var watchLaterUid: Long? = null
            val mappingSql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='dd_media_sync'", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (LibraryImportRules.plainTable(mappingSql)) {
                val fields = db.rawQuery("PRAGMA table_info(dd_media_sync)", null).use { c -> val names = mutableSetOf<String>(); while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name"))); names }
                if (fields.containsAll(listOf("id", "body"))) {
                    val body = db.rawQuery("SELECT body FROM dd_media_sync WHERE id=2 LIMIT 1", null).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (body != null && body.length <= 1024 * 1024) try { val uid = JSONObject(body).optLong("watch-later", -1); if (uid > 0 && tables["playlists"].orEmpty().any { it.getLong("uid") == uid }) watchLaterUid = uid } catch (_: Exception) { }
                }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            val records = mutableListOf<JSONObject>(); var unsupported = 0
            val channels = mutableMapOf<Long, String>(); val videos = mutableMapOf<Long, String>(); val groups = mutableMapOf<Long, String>()
            val index = mutableMapOf<String, Int>()
            fun add(p: JSONObject) = LibraryImportRules.append(records, index, p)
            for (row in tables["subscriptions"].orEmpty()) { val id = if (row.optInt("service_id", -1) == 0) LibraryImportRules.canonical(row.optString("url"), true) else null; if (id == null) unsupported++ else { channels[row.getLong("uid")] = id; add(LibraryImportRules.payload("subscription", id)) } }
            for (row in tables["streams"].orEmpty()) { val id = if (row.optInt("service_id", -1) == 0) LibraryImportRules.canonical(row.optString("url"), false) else null; if (id != null) videos[row.getLong("uid")] = id }
            for (row in tables["feed_group"].orEmpty()) { val id = LibraryImportRules.id("pipepipe-backup-profile", hash + ":" + row.getLong("uid")); groups[row.getLong("uid")] = id; add(LibraryImportRules.payload("profile", id).put("name", row.getString("name")).put("bgColor", row.optString("bg_color", "#333333").uppercase(java.util.Locale.ROOT)).put("textColor", row.optString("text_color", "#FFFFFF").uppercase(java.util.Locale.ROOT))) }
            for (row in tables["feed_group_subscription_join"].orEmpty()) { val profile = groups[row.getLong("group_id")]; val channel = channels[row.getLong("subscription_id")]; if (profile != null && channel != null) add(LibraryImportRules.payload("profile-member", "$profile:$channel").put("profileId", profile).put("channelId", channel)) else unsupported++ }
            for (row in tables["stream_history"].orEmpty()) { val id = videos[row.getLong("stream_id")]; if (id != null && row.optLong("access_date") > 0) add(LibraryImportRules.payload("history", id).put("watchedAtMs", row.getLong("access_date"))) else unsupported++ }
            for (row in tables["stream_state"].orEmpty()) { val id = videos[row.getLong("stream_id")]; if (id != null && row.optLong("progress_time", -1) >= 0) add(LibraryImportRules.payload("resume", id).put("positionMs", row.getLong("progress_time")).put("sessionId", LibraryImportRules.id("pipepipe-resume", id)).put("checkpointSeq", 0)) else unsupported++ }
            for (row in tables["playlists"].orEmpty()) {
                val items = JSONArray()
                for (item in tables["playlist_stream_join"].orEmpty().filter { it.getLong("playlist_id") == row.getLong("uid") }.sortedBy { it.getInt("join_index") }) { val id = videos[item.getLong("stream_id")]; if (id == null) unsupported++ else items.put(id) }
                val watch = row.getLong("uid") == watchLaterUid
                val p = LibraryImportRules.payload(if (watch) "watch-later" else "playlist", if (watch) "watch-later" else LibraryImportRules.id("pipepipe-backup-playlist", hash + ":" + row.getLong("uid"))).put("items", items)
                if (!watch) p.put("name", row.getString("name")); add(p)
            }
            return LibraryImportRules.Parsed("PipePipe database backup", LibraryImportRules.deduplicate(records), unsupported, tables["remote_playlists"].orEmpty().size)
        } finally { db.close() }
    }
}
