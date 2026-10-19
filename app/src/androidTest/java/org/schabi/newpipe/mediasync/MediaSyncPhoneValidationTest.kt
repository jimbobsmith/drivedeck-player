package org.schabi.newpipe.mediasync

import android.os.Build
import android.os.SystemClock
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.settings.MediaSyncSettingsFragment
import org.json.JSONArray
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.Migrations
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.stream.model.StreamStateEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.history.HistoryRecordManager
import java.io.File

/** Explicit opt-in, freshly installed Test package only. Never clears application data. */
@RunWith(AndroidJUnit4::class)
class MediaSyncPhoneValidationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun approvedPhone() {
        assertTrue("Native window inspection requires Android 10+", Build.VERSION.SDK_INT >= 29)
        assertEquals("com.drivedeck.player.test", context.packageName)
        assertFalse(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val permit = InstrumentationRegistry.getArguments().getString("freshPhonePermit") ?: error("Fresh-install permit required")
        assertTrue(permit.matches(Regex("[a-f0-9]{64}")))
        assertEquals(permit, File(context.noBackupFilesDir, "media-sync-phone-validation.permit").readText())
    }
    @Test fun freshInstallIsEmpty() {
        approvedPhone()
        val db = NewPipeDatabase.getInstance(context)
        assertFalse(MediaSyncSecrets(context).exists())
        assertNull(db.mediaSyncStateDAO().read())
        for (table in listOf("subscriptions", "streams", "stream_state")) {
            db.query("SELECT COUNT(*) FROM $table", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }

    private val channelA = "UC" + "a".repeat(22)
    private val channelB = "UC" + "b".repeat(22)
    private val videoA = "aBcDeFgH123"
    private val videoB = "bBcDeFgH123"
    private fun uid(db: AppDatabase, id: String): Long = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/watch?v=$id")).use { if (it.moveToFirst()) it.getLong(0) else -1 }
    private fun position(db: AppDatabase, id: String): Long = db.streamStateDAO().getState(uid(db, id)).blockingFirst().first().progressMillis
    private fun subscribed(db: AppDatabase, id: String) = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/channel/$id")).use { it.moveToFirst() }
    @Test fun actualAppCrossClientFlow() {
        approvedPhone()
        val args = InstrumentationRegistry.getArguments(); val phase = args.getString("phase", "receive")
        val db = NewPipeDatabase.getInstance(context); val secrets = MediaSyncSecrets(context)
        check(phase != "reset") { "Phone validation never resets data" }
        assertTrue("Import through settings first", secrets.exists())
        val client = MediaSyncClient(db, secrets.load())
        when (phase) {
            "receive" -> {
                assertEquals("synced", client.sync().getString("code"))
                assertTrue(subscribed(db, channelA)); assertEquals(90000L, position(db, videoA))
                // Verify the real player history lookup resolves its own local stream UID.
                val item = StreamInfoItem(0, "https://www.youtube.com/watch?v=$videoA", "test", StreamType.VIDEO_STREAM)
                assertEquals(90000L, HistoryRecordManager(context).loadStreamState(item).blockingGet()[0].progressMillis)
                db.runInTransaction {
                    if (!subscribed(db, channelB)) { val s = SubscriptionEntity(); s.serviceId = 0; s.url = "https://www.youtube.com/channel/$channelB"; s.name = "Local Android title"; s.avatarUrl = ""; db.subscriptionDAO().insert(s) }
                    var local = uid(db, videoB)
                    if (local < 0) local = db.streamDAO().insert(StreamEntity(serviceId = 0, url = "https://www.youtube.com/watch?v=$videoB", title = "Local Android video", streamType = StreamType.VIDEO_STREAM, duration = 600, uploader = "local"))
                    db.streamStateDAO().upsert(StreamStateEntity(local, 20000))
                }
                assertEquals("synced", client.sync().getString("code"))
            }
            "rewind" -> {
                assertEquals("synced", client.sync().getString("code")); assertFalse(subscribed(db, channelA)); assertEquals(15000L, position(db, videoA))
            }
            "readd" -> {
                val entry = SubscriptionEntity(); entry.serviceId = 0; entry.url = "https://www.youtube.com/channel/$channelA"; entry.name = "Phone re-add"; entry.avatarUrl = ""
                db.subscriptionDAO().insert(entry)
                assertEquals("synced", client.sync().getString("code")); assertTrue(subscribed(db, channelA))
            }
            "offline" -> {
                db.runInTransaction { db.streamStateDAO().upsert(StreamStateEntity(uid(db, videoB), 8000)) }
                assertEquals("offline", client.sync().getString("code")); assertEquals(8000L, position(db, videoB)); assertTrue(client.status().getInt("pending") > 0)
            }
            "conflict" -> {
                db.runInTransaction { db.streamStateDAO().upsert(StreamStateEntity(uid(db, videoA), 7000)) }
                assertEquals("conflict", client.sync().getString("code")); assertEquals(7000L, position(db, videoA))
                assertEquals("synced", client.sync("keep-local").getString("code")); assertEquals(7000L, position(db, videoA))
            }
            "retry" -> { assertEquals("synced", client.sync().getString("code")); assertEquals(8000L, position(db, videoB)); assertEquals(0, client.status().getInt("pending")) }
            else -> fail("Unknown test phase")
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    // Inspect only this Test app's process-local native windows; never connect to
    // the shared phone-wide UiAutomation service or inspect another app.
    private fun windows() = android.view.inspector.WindowInspector.getGlobalWindowViews()
    private fun descendants(view: android.view.View): List<android.view.View> =
        listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun clickText(scenario: ActivityScenario<SettingsActivity>, text: String) {
        waitFor("Missing enabled control: $text") {
            var clicked = false
            scenario.onActivity {
                for (root in windows().asReversed()) {
                    val view = descendants(root).firstOrNull { it is android.widget.TextView && it.text.toString() == text && it.isShown } ?: continue
                    var target: android.view.View? = view
                    while (target != null) {
                        if (target.isClickable && target.isEnabled) { clicked = target.performClick(); break }
                        target = target.parent as? android.view.View
                    }
                    if (clicked) break
                }
            }
            clicked
        }
    }
    private fun confirmDialog(scenario: ActivityScenario<SettingsActivity>) {
        waitFor("Dialog confirmation missing") {
            var clicked = false
            scenario.onActivity {
                for (root in windows().asReversed()) {
                    val button = root.findViewById<android.view.View>(android.R.id.button1)
                    if (button?.isShown == true && button.isEnabled) { clicked = button.performClick(); break }
                }
            }
            clicked
        }
    }
    private fun settings(): ActivityScenario<SettingsActivity> {
        val scenario = ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java).setAction(Intent.ACTION_MAIN))
        scenario.onActivity { it.supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, MediaSyncSettingsFragment()).commitNow() }
        return scenario
    }
    private fun waitStatus(scenario: ActivityScenario<SettingsActivity>, resource: Int) {
        waitFor("Expected settings status") {
            var matched = false
            scenario.onActivity {
                val fragment = it.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment
                matched = fragment.findPreference<Preference>("media_sync_status")?.summary == context.getString(resource)
            }
            matched
        }
    }
    @Test fun protectedEnrollmentThroughNativeSettings() {
        approvedPhone()
        assertFalse(MediaSyncSecrets(context).exists())
        // Only the external file choice is intercepted. The production launch, URI read,
        // masked passphrase dialog, KDF, Keystore and status rendering execute normally.
        val selected = Intent().setData(android.net.Uri.fromFile(File(context.cacheDir, "media-sync-enrollment.json")))
        val monitor = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addDataType("*/*") },
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, selected), true)
        try {
            settings().use { scenario ->
                waitStatus(scenario, R.string.media_sync_disconnected)
                clickText(scenario, context.getString(R.string.media_sync_import))
                waitFor("Masked passphrase dialog missing") {
                    var filled = false
                    scenario.onActivity {
                        val field = windows().asReversed().flatMap { descendants(it) }.filterIsInstance<android.widget.EditText>().firstOrNull { it.isShown }
                        if (field != null) {
                            assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, field.inputType and android.text.InputType.TYPE_MASK_VARIATION)
                            field.setText("synthetic test passphrase"); filled = true
                        }
                    }
                    filled
                }
                confirmDialog(scenario)
                waitStatus(scenario, R.string.media_sync_ready)
                assertTrue(MediaSyncSecrets(context).exists())
                clickText(scenario, context.getString(R.string.media_sync_now))
                waitStatus(scenario, R.string.media_sync_synced)
                assertEquals(1, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun settingsExposeManualControlsInBothOrientations() {
        approvedPhone()
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java).setAction(Intent.ACTION_MAIN)).use { scenario ->
            scenario.onActivity { it.supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, MediaSyncSettingsFragment()).commitNow() }
            waitFor("Sync button disabled") {
                var ready = false
                scenario.onActivity { ready = (it.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment).findPreference<Preference>("media_sync_sync")!!.isEnabled }
                ready
            }
            clickText(scenario, context.getString(R.string.media_sync_now))
            waitStatus(scenario, R.string.media_sync_synced)
            for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
                scenario.onActivity { it.requestedOrientation = orientation }; SystemClock.sleep(1200)
                scenario.onActivity {
                    val fragment = it.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment
                    assertNotNull(fragment.findPreference<Preference>("media_sync_sync")); assertNotNull(fragment.findPreference<Preference>("media_sync_disconnect"))
                    assertEquals(it.getString(R.string.media_sync_title), fragment.preferenceScreen.title)
                }
                scenario.onActivity {
                    val view = it.window.decorView
                    val screenshot = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(screenshot))
                    File(context.cacheDir, "media-sync-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
                }
            }
        }
    }
    @Test fun disconnectPreservesLocalLibrary() {
        approvedPhone()
        val db = NewPipeDatabase.getInstance(context)
        assertTrue(subscribed(db, channelB)); assertEquals(7000L, position(db, videoA)); assertEquals(8000L, position(db, videoB))
        settings().use { scenario ->
            waitFor("Disconnect disabled") {
                var enabled = false
                scenario.onActivity { enabled = (it.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment).findPreference<Preference>("media_sync_disconnect")!!.isEnabled }
                enabled
            }
            clickText(scenario, context.getString(R.string.media_sync_disconnect))
            confirmDialog(scenario)
            waitStatus(scenario, R.string.media_sync_disconnected)
        }
        assertFalse(MediaSyncSecrets(context).exists()); assertNull(db.mediaSyncStateDAO().read())
        assertTrue(subscribed(db, channelB)); assertEquals(7000L, position(db, videoA)); assertEquals(8000L, position(db, videoB))
    }
    @Test fun enrollmentTamperingFailsAndKeysStayOutsideRoomState() {
        approvedPhone()
        val e = JSONObject(File(context.cacheDir, "media-sync-enrollment.json").readText())
        try { MediaSyncCrypto.unprotect(e, "wrong test passphrase"); fail("Wrong passphrase accepted") } catch (_: javax.crypto.AEADBadTagException) { }
        val config = MediaSyncCrypto.unprotect(e, "synthetic test passphrase")
        val db = NewPipeDatabase.getInstance(context); val client = MediaSyncClient(db, config)
        val body = db.mediaSyncStateDAO().read()!!
        assertFalse(body.contains(config.getJSONObject("credential").getString("token")))
        assertFalse(body.contains(config.getJSONObject("keys").getString("encryptionKey")))
        assertFalse(body.contains(config.getJSONObject("keys").getString("routingKey")))
        val changed = JSONObject(config.toString()); changed.getJSONObject("keys").put("encryptionKey", MediaSyncCrypto.encode(MediaSyncCrypto.bytes(32)))
        try { MediaSyncClient(db, changed); fail("Changed keys accepted") } catch (_: SyncFailure) { }
        assertEquals(body, db.mediaSyncStateDAO().read()); assertNotNull(client.status())
    }
}
