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

    private fun fetch(id: String): Pair<String, String>? {
        val url = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            return parse(response.body?.string().orEmpty())
        }
    }

    /** One bounded pass; returns how many streams were updated. Only rows still holding the placeholder title change. */
    fun run(db: AppDatabase, limit: Int = 20): Int {
        val wanted = ArrayList<Triple<Long, String, String>>()
        db.query("SELECT uid,url,title FROM streams WHERE service_id=0 AND title LIKE 'Synced %: %' LIMIT ?", arrayOf(limit)).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2)
                val id = VIDEO_ID.matchEntire(c.getString(1))?.groupValues?.get(1)
                if (id != null && isPlaceholder(title)) wanted.add(Triple(c.getLong(0), id, title))
            }
        }
        var updated = 0
        for ((uid, id, placeholder) in wanted) {
            val info = try { fetch(id) } catch (_: Exception) { null } ?: continue
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE streams SET title=?, uploader=CASE WHEN uploader='' THEN ? ELSE uploader END WHERE uid=? AND title=?", arrayOf(info.first, info.second, uid, placeholder))
            updated += 1
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
        return updated
    }
}
