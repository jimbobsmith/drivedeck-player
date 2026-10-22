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

/** Targets an explicitly selected emulator only. Real default app Room DB + Android Keystore. */
@RunWith(AndroidJUnit4::class)
class MediaSyncIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun emulator() { assertTrue("Dedicated emulator required", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") }
    private val channelA = "UC" + "a".repeat(22)
    private val channelB = "UC" + "b".repeat(22)
    private val videoA = "aBcDeFgH123"
    private val videoB = "bBcDeFgH123"
    private fun uid(db: AppDatabase, id: String): Long = db.query("SELECT uid FROM streams WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/watch?v=$id")).use { if (it.moveToFirst()) it.getLong(0) else -1 }
    private fun position(db: AppDatabase, id: String): Long = db.streamStateDAO().getState(uid(db, id)).blockingFirst().first().progressMillis
    private fun subscribed(db: AppDatabase, id: String) = db.query("SELECT uid FROM subscriptions WHERE service_id=0 AND url=?", arrayOf("https://www.youtube.com/channel/$id")).use { it.moveToFirst() }
    @Test fun actualAppCrossClientFlow() {
        emulator()
        val args = InstrumentationRegistry.getArguments(); val phase = args.getString("phase", "receive")
        val db = NewPipeDatabase.getInstance(context); val secrets = MediaSyncSecrets(context)
        if (phase == "reset") {
            db.runInTransaction { db.mediaSyncStateDAO().clear(); db.subscriptionDAO().deleteSubscription(0, "https://www.youtube.com/channel/$channelA"); db.subscriptionDAO().deleteSubscription(0, "https://www.youtube.com/channel/$channelB"); for (id in listOf(videoA, videoB)) { val local = uid(db, id); if (local > 0) db.streamStateDAO().deleteState(local) } }
            secrets.delete(); return
        }
        if (!secrets.exists()) {
            val e = JSONObject(File(context.cacheDir, "media-sync-enrollment.json").readText())
            secrets.save(MediaSyncCrypto.unprotect(e, "synthetic test passphrase"))
        }
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
    @Test fun settingsExposeManualControlsInBothOrientations() {
        emulator()
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            scenario.onActivity { it.supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, MediaSyncSettingsFragment()).commitNow() }
            for ((orientation, name) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape")) {
                scenario.onActivity { it.requestedOrientation = orientation }; SystemClock.sleep(1200)
                scenario.onActivity {
                    val fragment = it.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment
                    assertNotNull(fragment.findPreference<Preference>("media_sync_library")); assertNotNull(fragment.findPreference<Preference>("media_sync_sync")); assertNotNull(fragment.findPreference<Preference>("media_sync_disconnect"))
                    assertEquals(it.getString(R.string.media_sync_title), fragment.preferenceScreen.title)
                }
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.cacheDir, "media-sync-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
            }
        }
    }
    @Test fun enrollmentTamperingFailsAndKeysStayOutsideRoomState() {
        emulator()
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
    @Test fun migration901Through903PreservesExistingLibraryAndProfiles() {
        emulator()
        val name = "media-sync-migration-validation.db"; context.deleteDatabase(name)
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("media-sync-schema-901.json").use { it.readBytes().toString(Charsets.UTF_8) }).getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: JSONArray(); for (j in 0 until indexes.length()) raw.execSQL(indexes.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL("INSERT INTO subscriptions(service_id,url,name,notification_mode) VALUES(0,?,'Preserved migration title',0)", arrayOf("https://www.youtube.com/channel/$channelA"))
            raw.execSQL("INSERT INTO feed_group(uid,name,icon_id,sort_order) VALUES(1,'Preserved profile',16,3)")
            raw.execSQL("INSERT INTO feed_group_subscription_join(group_id,subscription_id) SELECT 1,uid FROM subscriptions")
            raw.version = 901
        } finally { raw.close() }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(Migrations.MIGRATION_901_902, Migrations.MIGRATION_902_903).build()
        try {
            assertTrue(subscribed(migrated, channelA)); assertNull(migrated.mediaSyncStateDAO().read())
            migrated.mediaSyncStateDAO().write(MediaSyncState(body = "{}")); assertEquals("{}", migrated.mediaSyncStateDAO().read())
            val profile = migrated.feedGroupDAO().getGroup(1).blockingGet()!!
            assertEquals("Preserved profile", profile.name); assertEquals(3L, profile.sortOrder)
            assertEquals("#333333", profile.bgColor); assertEquals("#FFFFFF", profile.textColor)
            assertEquals(1, migrated.feedGroupDAO().getSubscriptionIdsFor(1).blockingFirst().size)
        }
        finally { migrated.close(); context.deleteDatabase(name) }
    }
}
