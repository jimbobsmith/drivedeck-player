package org.schabi.newpipe.mediasync

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.database.AppDatabase
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fills in titles for videos that arrived through sync as bare IDs ("Synced video: id"), without any
 * change to the sync protocol. Title and channel name come from YouTube's public oEmbed endpoint; nothing
 * is sent to the sync service. Channel names and pictures are filled in by the normal feed refresh.
 */
object MediaSyncHydrator {
    private val PLACEHOLDER = Regex("^Synced (?:video|resume): ")
    private val VIDEO_ID = Regex("https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})")
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    fun isPlaceholder(title: String) = PLACEHOLDER.containsMatchIn(title)

    private fun clean(value: String, max: Int) = value.map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("").trim().take(max)

    /** Parses an oEmbed answer into (title, author); null when it has no usable title or is oversized. */
    fun parse(body: String): Pair<String, String>? {
        if (body.length > 16384) return null
        val json = try { JSONObject(body) } catch (_: Exception) { return null }
        val title = clean(json.optString("title"), 300)
        return if (title.isEmpty()) null else title to clean(json.optString("author_name"), 200)
    }

    private const val MAX_BODY = 16384

    /** Reads at most MAX_BODY bytes; null for a longer body, so an oversized answer is never buffered whole. */
    private fun readBounded(input: java.io.InputStream): String? {
        val buffer = ByteArray(MAX_BODY + 1); var total = 0
        while (total < buffer.size) { val n = input.read(buffer, total, buffer.size - total); if (n < 0) break; total += n }
        return if (total > MAX_BODY) null else String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun fetch(id: String): Pair<String, String>? {
        val url = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.byteStream()?.use { readBounded(it) } ?: return null
            return parse(body)
        }
    }

    /** Rows that failed or were rejected during this process run. They stay eligible after a restart, but must not block later candidates. */
    private val skipped = LinkedHashSet<Long>()

    /** One bounded pass; returns how many streams were updated. Only rows still holding the placeholder title change. */
    fun run(db: AppDatabase, limit: Int = 20): Int {
        val wanted = ArrayList<Triple<Long, String, String>>()
        val exclude = synchronized(skipped) { if (skipped.size > 500) skipped.clear(); skipped.joinToString(",") }
        val skip = if (exclude.isEmpty()) "" else " AND uid NOT IN ($exclude)"
        db.query("SELECT uid,url,title FROM streams WHERE service_id=0 AND title LIKE 'Synced %: %'$skip ORDER BY uid DESC LIMIT ?", arrayOf(limit)).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2)
                val id = VIDEO_ID.matchEntire(c.getString(1))?.groupValues?.get(1)
                if (id != null && isPlaceholder(title)) wanted.add(Triple(c.getLong(0), id, title)) else synchronized(skipped) { skipped.add(c.getLong(0)) }
            }
        }
        var updated = 0
        for ((uid, id, placeholder) in wanted) {
            val info = try { fetch(id) } catch (_: Exception) { null }
            if (info == null) { synchronized(skipped) { skipped.add(uid) }; continue }
            // A Room transaction, so Room refreshes the Flowable queries (history, playlists) that join streams.
            db.runInTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE streams SET title=?, uploader=CASE WHEN uploader='' THEN ? ELSE uploader END WHERE uid=? AND title=?", arrayOf(info.first, info.second, uid, placeholder))
            }
            updated += 1
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
        return updated
    }
}
