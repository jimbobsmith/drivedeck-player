package org.schabi.newpipe.mediasync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import org.schabi.newpipe.util.NavigationHelper
import java.io.File
import java.net.URI

/** A phase of the explicitly reserved real-app campaign. Never uses an in-memory/model DB. */
@RunWith(AndroidJUnit4::class)
class RealAppSyncE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val channelA = "UC" + "a".repeat(22)
    private val channelB = "UC" + "b".repeat(22)
    private fun snapshot(client: MediaSyncClient): JSONObject {
        val db = NewPipeDatabase.getInstance(context)
        val subscriptions = JSONArray()
        db.query("SELECT url FROM subscriptions WHERE service_id=0 ORDER BY url", null).use {
            while (it.moveToNext()) subscriptions.put(it.getString(0).substringAfterLast('/'))
        }
        val resumes = JSONArray()
        db.query("SELECT s.url,p.progress_time FROM streams s JOIN stream_state p ON p.stream_id=s.uid WHERE s.service_id=0", null).use { while(it.moveToNext()) resumes.put(JSONObject().put("id", it.getString(0).substringAfter("v=")).put("positionMs", it.getLong(1))) }
        return JSONObject().put("subscriptions", subscriptions).put("resumes", resumes)
            .put("library", JSONArray(if(client.status().optBoolean("libraryEnabled")) MediaSyncLibrary(db).snapshot() else emptyList<JSONObject>()))
            .put("localTheme", PreferenceManager.getDefaultSharedPreferences(context).getString(context.getString(R.string.theme_key), "default"))
            .put("profiles", JSONArray(MediaSyncProfiles(db).snapshot().filter { it.getString("kind") == "profile" }))
            .put("memberships", JSONArray(MediaSyncProfiles(db).snapshot().filter { it.getString("kind") == "profile-member" }))
            .put("status", client.status())
    }
    private fun visibleProof(phase: String, expected: String): JSONObject {
        var visible = ""
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { if (args.getString("screen") == "history") NavigationHelper.openStatisticFragment(it.supportFragmentManager) else NavigationHelper.openSubscriptionFragment(it.supportFragmentManager) }
            val deadline = SystemClock.elapsedRealtime() + 15000
            fun text(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): String {
                if (node == null || depth > 30) return ""
                return buildString { append(node.text ?: ""); append('\n'); for (i in 0 until node.childCount) append(text(node.getChild(i), depth + 1)) }
            }
            do {
                instrumentation.waitForIdleSync()
                visible = text(instrumentation.uiAutomation.rootInActiveWindow)
                if (visible.contains(expected)) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            val directory = File(context.filesDir, "e2e").apply { mkdirs() }
            File(directory, "$phase-ui.txt").writeText(visible)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "$phase-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertTrue("Actual subscription screen did not show $expected", visible.contains(expected))
        }
        return JSONObject().put("expectedText", expected).put("visible", true).put("screen", "production SubscriptionFragment")
    }
    @Test fun runPhase() {
        assertTrue("Fresh AOSP emulator only", Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertEquals("com.drivedeck.player.test", context.packageName)
        val phase = args.getString("phase") ?: error("phase required")
        val path = args.getString("enrollment") ?: error("enrollment required")
        require(path.startsWith("/data/local/tmp/dd-sync-e2e/"))
        val config = MediaSyncCrypto.unprotect(JSONObject(File(path).readText()), "synthetic-e2e-only-20261028")
        require(URI(config.getString("endpoint")).host == "127.0.0.1")
        val db = NewPipeDatabase.getInstance(context)
        val secrets = MediaSyncSecrets(context)
        if (!secrets.exists()) {
            assertEquals("receive-desktop", phase)
            assertNull(db.mediaSyncStateDAO().read())
            secrets.save(config)
        }
        val client = MediaSyncClient(db, secrets.load())
        client.enableProfiles()
        val started = SystemClock.elapsedRealtime()
        val result: JSONObject
        val extra = JSONObject()
        val repository = FeedDatabaseManager(context)
        fun group(name: String): Long = db.query("SELECT uid FROM feed_group WHERE name=?", arrayOf(name)).use { require(it.moveToFirst()) { "Missing group: $name" }; it.getLong(0) }
        fun rename(before: String, after: String): Long { val uid = group(before); val old = repository.getGroup(uid).blockingGet()!!; repository.updateGroup(old.copy(name = after)).blockingAwait(); return uid }
        when (phase) {
            "receive-desktop" -> {
                result = client.sync()
                val state = snapshot(client)
                val diagnostics = JSONObject().put("phase", phase).put("state", state).put("syncResult", result)
                    .put("loopbackCleartextPermitted", android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
                try {
                    val probe = okhttp3.OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                    probe.newCall(okhttp3.Request.Builder().url(config.getString("endpoint") + "/healthz").build()).execute().use { diagnostics.put("healthStatus", it.code) }
                } catch (error: Exception) { diagnostics.put("healthError", error.javaClass.simpleName + ": " + error.message?.take(200)) }
                instrumentation.sendStatus(0, Bundle().apply { putString("dd_e2e_checkpoint", diagnostics.toString()) })
                File(context.filesDir, "e2e").mkdirs()
                File(context.filesDir, "e2e/$phase-checkpoint.json").writeText(diagnostics.toString(2))
                assertEquals("Explicit production sync failed: " + diagnostics, "synced", result.optString("code"))
                assertTrue(state.getJSONArray("subscriptions").toString().contains(channelA))
                assertTrue(state.getJSONArray("profiles").toString().contains("Desktop science"))
            }
            "send-android" -> {
                val manager = SubscriptionManager(context)
                val inserted = manager.insertAll(listOf(SubscriptionItem(0, "https://www.youtube.com/channel/$channelB", "Android synthetic channel")))
                val repository = FeedDatabaseManager(context)
                val uid = repository.createGroup("Android projects", FeedGroupIcon.PERSON, "#224466", "#FFFFFF").blockingGet()!!
                repository.updateSubscriptionsForGroup(uid, listOf(inserted.single().uid)).blockingAwait()
                result = client.sync()
            }
            "rename-android" -> {
                val uid = rename("Android projects", "Android projects renamed")
                val channelUid = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/channel/$channelA")).use { require(it.moveToFirst()); it.getLong(0) }
                repository.updateSubscriptionsForGroup(uid, listOf(channelUid)).blockingAwait()
                result = client.sync()
            }
            "delete-category" -> { repository.deleteGroup(group("Android projects renamed")).blockingAwait(); result = client.sync() }
            "offline-rename" -> { rename("Desktop science", "Android queued science"); result = client.sync(); assertTrue(client.status().getInt("pending") > 0) }
            "conflict-rename" -> { rename("Android queued science", "Android conflict choice"); result = client.sync() }
            "library-enable" -> {
                client.enableLibrary(); val initial = client.sync(); extra.put("initialLibraryResult", initial)
                result = if(initial.optString("code") == "conflict") { assertEquals(1, initial.getInt("conflicts")); extra.put("explicitResolution", "use-remote for initial empty Watch Later"); client.sync("use-remote") } else initial
            }
            "observe-history" -> {
                result = client.sync(); val state = snapshot(client)
                assertTrue(state.getJSONArray("resumes").toString().contains("12345"))
                assertTrue(state.getJSONArray("library").toString().contains("1800000000000"))
            }
            "mutate-progress" -> {
                val uid = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/watch?v=abcdefghijk")).use { require(it.moveToFirst()); it.getLong(0) }
                db.streamStateDAO().upsert(StreamStateEntity(uid, 5000))
                db.streamHistoryDAO().insert(StreamHistoryEntity(uid, OffsetDateTime.ofInstant(Instant.ofEpochMilli(1800000001000), ZoneOffset.UTC), 1))
                result = client.sync()
            }
            "set-local-theme" -> {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putString(context.getString(R.string.theme_key), context.getString(R.string.dark_theme_key)).commit()
                result = client.sync()
            }
            "repeat" -> result = client.sync()
            "remove-android" -> {
                SubscriptionManager(context).deleteGlobalSubscription(0, "https://www.youtube.com/channel/$channelB").blockingAwait()
                result = client.sync()
            }
            else -> error("unknown bounded phase")
        }
        assertEquals(if(phase == "offline-rename") "offline" else "synced", result.getString("code"))
        val state = snapshot(client).put("phase", phase).put("syncTrigger", "explicit production MediaSyncClient.sync (same settings action)")
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("serviceResult", result).put("extra", extra)
        state.put("ui", visibleProof(phase, args.getString("expectedUi") ?: if (phase == "receive-desktop") "Desktop science" else "Android projects"))
        File(context.filesDir, "e2e/$phase.json").writeText(state.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("dd_e2e_state", state.toString()) })
    }
}
