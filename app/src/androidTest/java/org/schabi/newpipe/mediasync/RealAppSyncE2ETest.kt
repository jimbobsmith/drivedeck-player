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
        return JSONObject().put("subscriptions", subscriptions)
            .put("profiles", JSONArray(MediaSyncProfiles(db).snapshot().filter { it.getString("kind") == "profile" }))
            .put("memberships", JSONArray(MediaSyncProfiles(db).snapshot().filter { it.getString("kind") == "profile-member" }))
            .put("status", client.status())
    }
    private fun visibleProof(phase: String, expected: String): JSONObject {
        var visible = ""
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { NavigationHelper.openSubscriptionFragment(it.supportFragmentManager) }
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
        client.enableLibrary(); client.enableProfiles()
        val started = SystemClock.elapsedRealtime()
        val result: JSONObject
        when (phase) {
            "receive-desktop" -> {
                result = client.sync()
                val state = snapshot(client)
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
            "repeat" -> result = client.sync()
            "remove-android" -> {
                SubscriptionManager(context).deleteGlobalSubscription(0, "https://www.youtube.com/channel/$channelB").blockingAwait()
                result = client.sync()
            }
            else -> error("unknown bounded phase")
        }
        assertEquals("synced", result.getString("code"))
        val state = snapshot(client).put("phase", phase).put("syncTrigger", "explicit production MediaSyncClient.sync (same settings action)")
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("serviceResult", result)
        state.put("ui", visibleProof(phase, if (phase == "receive-desktop") "Desktop science" else "Android projects"))
        File(context.filesDir, "e2e/$phase.json").writeText(state.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("dd_e2e_state", state.toString()) })
    }
}
