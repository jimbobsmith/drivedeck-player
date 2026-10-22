package org.schabi.newpipe.mediasync

import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.subscription.FeedGroupIcon
import org.schabi.newpipe.local.subscription.ProfileSelection
import java.util.UUID
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.widget.EditText
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity
import org.schabi.newpipe.settings.MediaSyncSettingsFragment
import org.schabi.newpipe.local.subscription.dialog.FeedGroupDialog
import androidx.preference.Preference
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.local.feed.FeedViewModel
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import java.io.File

/** Synthetic in-memory Room only; execution requires a separately authorized dedicated emulator. */
@RunWith(AndroidJUnit4::class)
class MediaSyncProfilesTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val channel = "UC" + "a".repeat(22)
    private fun metadata(profile: String = id, name: String = "Trips") = JSONObject().put("kind", "profile").put("provider", "youtube").put("version", 3).put("id", profile).put("name", name).put("bgColor", "#12aBcD").put("textColor", "#FFFFFF")
    private fun member(profile: String = id, channel: String = this.channel) = JSONObject().put("kind", "profile-member").put("provider", "youtube").put("version", 3).put("id", "$profile:$channel").put("profileId", profile).put("channelId", channel)
    private fun record(p: JSONObject, remove: Boolean = false) = JSONObject().put("payload", p).put("operation", if (remove) "remove" else "put")
    private fun database(transaction: Boolean = true, block: (AppDatabase) -> Unit) {
        assertTrue("Dedicated emulator only", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).allowMainThreadQueries().build()
        try { if (transaction) db.runInTransaction { block(db) } else block(db) } finally { db.close() }
    }
    private fun subscribe(db: AppDatabase, channel: String = this.channel, service: Int = 0): Long {
        val row = SubscriptionEntity().apply { serviceId = service; url = if(service == 0) "https://www.youtube.com/channel/$channel" else "https://example.invalid/channel"; name = "Fixture"; avatarUrl = ""; notificationMode = 0 }
        return db.subscriptionDAO().insert(row)
    }
    private fun count(db: AppDatabase, table: String) = db.query("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    @Test fun memberBeforeGlobalAndMetadataSurvivesAdapterRestartWithoutSubscribing() = database { db ->
        MediaSyncProfiles(db).project(record(member()))
        assertEquals(0, count(db, "subscriptions")); assertEquals(0, count(db, "feed_group_subscription_join"))
        assertEquals(1, MediaSyncProfiles(db).snapshot().count { it.getString("kind") == "profile-member" })
        subscribe(db); MediaSyncProfiles(db).project(record(metadata()))
        val snapshot = MediaSyncProfiles(db).snapshot()
        assertEquals(1, count(db, "feed_group_subscription_join"))
        assertEquals("Trips", snapshot.single { it.getString("kind") == "profile" }.getString("name"))
        assertEquals("#12ABCD", snapshot.single { it.getString("kind") == "profile" }.getString("bgColor"))
    }
    @Test fun profileSupportsMoreThanOneHundredChannelsAndSameNameDistinctIdentity() = database { db ->
        val profiles = MediaSyncProfiles(db); profiles.project(record(metadata()))
        val other = UUID.randomUUID().toString(); profiles.project(record(metadata(other)))
        repeat(103) { index ->
            val channel = "UC" + index.toString().padStart(22, '0'); subscribe(db, channel)
            profiles.project(record(member(channel = channel)))
        }
        val snapshot = profiles.snapshot()
        assertEquals(103, snapshot.count { it.getString("kind") == "profile-member" })
        assertEquals(2, snapshot.count { it.getString("kind") == "profile" && it.getString("name") == "Trips" })
    }
    @Test fun deleteProfileKeepsGlobalAndSuppressesStaleConcurrentMember() = database { db ->
        subscribe(db); val profiles = MediaSyncProfiles(db)
        profiles.project(record(metadata())); profiles.project(record(member())); profiles.project(record(metadata(), true))
        profiles.project(record(member()), profileDeleted = { true })
        assertEquals(1, count(db, "subscriptions")); assertEquals(0, count(db, "feed_group"))
        assertTrue(profiles.snapshot().isEmpty())
    }
    @Test fun globalUnsubscribePrunesDeferredAndCannotBeResurrectedByStaleMember() = database { db ->
        val profiles = MediaSyncProfiles(db); profiles.project(record(member()))
        profiles.removeChannel(channel); profiles.project(record(member()), explicitlyUnsubscribed = { true })
        assertEquals(0, count(db, "subscriptions"))
        assertFalse(profiles.snapshot().any { it.getString("kind") == "profile-member" })
    }
    @Test fun membershipRemovalKeepsGlobalAndOtherProfilesAndNonYoutubeMembers() = database { db ->
        val sub = subscribe(db); val foreign = subscribe(db, service = 1); val profiles = MediaSyncProfiles(db)
        profiles.project(record(metadata())); profiles.project(record(member()))
        val other = UUID.randomUUID().toString(); profiles.project(record(metadata(other))); profiles.project(record(member(other)))
        val uid = db.query("SELECT uid FROM feed_group ORDER BY uid LIMIT 1", null).use { it.moveToFirst(); it.getLong(0) }
        val alias = SubscriptionEntity().apply { serviceId = 0; url = "https://www.youtube.com/@local-alias"; name = "Local alias"; avatarUrl = ""; notificationMode = 0 }
        val aliasUid = db.subscriptionDAO().insert(alias)
        db.feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(uid, foreign), FeedGroupSubscriptionEntity(uid, aliasUid)))
        profiles.project(record(member(), true))
        assertEquals(3, count(db, "subscriptions")); assertEquals(3, count(db, "feed_group_subscription_join"))
        assertEquals(1, profiles.snapshot().count { it.getString("kind") == "profile-member" })
        assertTrue(sub > 0)
    }
    @Test fun disconnectKeepsUuidMappingAndEmptyProfiles() = database { db ->
        db.feedGroupDAO().insert(FeedGroupEntity(0, "Local empty", FeedGroupIcon.PERSON))
        val before = MediaSyncProfiles(db).snapshot().single().getString("id")
        db.mediaSyncStateDAO().write(MediaSyncState(body = "temporary enrollment")); db.mediaSyncStateDAO().clear()
        assertEquals(before, MediaSyncProfiles(db).snapshot().single().getString("id"))
    }
    @Test fun selectedProfilePersistsAcrossObserversAndDeletedSelectionFallsBack() = database(transaction = false) { db ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = db.feedGroupDAO().insert(FeedGroupEntity(0, "Selected", FeedGroupIcon.PERSON))
        try {
            ProfileSelection.select(context, uid)
            assertEquals(uid, ProfileSelection.current(context.applicationContext))
            assertEquals(uid, ProfileSelection.observe(context, db).blockingFirst().uid)
            assertEquals(uid, ProfileSelection.observe(context.applicationContext, db).blockingFirst().uid)
            db.feedGroupDAO().delete(uid)
            assertEquals(FeedGroupEntity.GROUP_ALL_ID, ProfileSelection.observe(context, db).blockingFirst().uid)
            assertEquals(FeedGroupEntity.GROUP_ALL_ID, ProfileSelection.current(context))
        } finally { ProfileSelection.select(context, FeedGroupEntity.GROUP_ALL_ID) }
    }
    @Test fun strictProfileSchemasRejectUnknownPrivateFieldsAndWrongIdentity() {
        for (p in listOf(metadata().put("cookie", "secret"), member().put("id", UUID.randomUUID().toString()), metadata().put("version", 2), metadata().put("bgColor", "red"), metadata().put("name", 123), metadata().put("name", JSONObject.NULL))) {
            try { MediaSyncCrypto.validatePayload(p); fail("Expected schema rejection") } catch (_: SyncFailure) { }
        }
    }
    @Test fun desktopEncryptedProfileVectorsDecryptAndAndroidCounterpartsAreExported() {
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("node-profile-fixtures.json").use { it.readBytes().toString(Charsets.UTF_8) })
        val keys = fixture.getJSONObject("keys"); val owner = fixture.getString("owner")
        val cases = fixture.getJSONArray("cases"); val counterparts = JSONArray()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i); val payload = case.getJSONObject("payload")
            val opened = MediaSyncCrypto.open(keys, owner, case.getJSONObject("mutation"))
            assertEquals(payload.toString(), opened.toString())
            assertEquals(case.getString("recordKey"), MediaSyncCrypto.recordKey(keys, payload))
            val header = JSONObject(case.getJSONObject("mutation").toString()).put("mutationId", UUID.randomUUID().toString())
            counterparts.put(JSONObject().put("payload", payload).put("recordKey", MediaSyncCrypto.recordKey(keys, payload)).put("mutation", MediaSyncCrypto.seal(keys, owner, header, payload)))
        }
        val output = JSONObject().put("warning", "PUBLIC SYNTHETIC TEST KEYS ONLY; NEVER USE FOR A REAL LIBRARY")
            .put("keys", keys).put("owner", owner).put("cases", counterparts)
        File(instrumentation.targetContext.cacheDir, "android-profile-fixtures.json").writeText(output.toString())
    }
    @Test fun realProfileDialogAndOptInRemainAccessibleInPortraitAndLandscape() {
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                scenario.onActivity { it.requestedOrientation = orientation }; SystemClock.sleep(1000)
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, MediaSyncSettingsFragment()).commitNow()
                    val settings = activity.supportFragmentManager.findFragmentById(R.id.settings_fragment_holder) as MediaSyncSettingsFragment
                    assertNotNull(settings.findPreference<Preference>("media_sync_profiles"))
                    assertTrue(activity.getString(R.string.media_sync_profiles_confirm).contains("older clients stop syncing"))
                    FeedGroupDialog.newInstance().showNow(activity.supportFragmentManager, "profile-test")
                }
                SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    val dialog = (activity.supportFragmentManager.findFragmentByTag("profile-test") as FeedGroupDialog).requireDialog()
                    val name = dialog.findViewById<EditText>(R.id.group_name_input)
                    assertNotNull(name); assertTrue(name.isShown)
                    assertTrue(dialog.findViewById<Button>(R.id.profile_colors_button).isShown)
                    val confirm = dialog.findViewById<Button>(R.id.confirm_button)
                    assertTrue(confirm.isShown); assertTrue(confirm.height > 0)
                    (activity.supportFragmentManager.findFragmentByTag("profile-test") as FeedGroupDialog).dismissNow()
                }
            }
        }
    }
    @Test fun activeProfileSubscriptionActionsKeepGlobalInvariantAndFeedFollowsChanges() {
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = NewPipeDatabase.getInstance(context)
        val profile = db.feedGroupDAO().insert(FeedGroupEntity(0, "Fixture active profile", FeedGroupIcon.PERSON))
        val url = "https://www.youtube.com/channel/$channel"
        val row = SubscriptionEntity().apply { serviceId = 0; this.url = url; name = "Fixture channel"; avatarUrl = ""; notificationMode = 0 }
        val info = ChannelInfo(0, channel, url, url, "Fixture", ListLinkHandler(url, url, channel, emptyList(), emptyList())).apply { relatedItems = emptyList() }
        val manager = SubscriptionManager(context)
        val store = ViewModelStore()
        val observed = AtomicLong(Long.MIN_VALUE)
        var subscriptionObserver: io.reactivex.rxjava3.subscribers.TestSubscriber<List<SubscriptionEntity>>? = null
        fun expectSubscriptionCount(expected: Int) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (subscriptionObserver!!.values().lastOrNull()?.size != expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(expected, subscriptionObserver!!.values().lastOrNull()?.size)
        }
        lateinit var feed: FeedViewModel
        try {
            ProfileSelection.select(context, profile)
            instrumentation.runOnMainSync {
                feed = FeedViewModel(context); store.put("profile-feed-test", feed)
                feed.profileLiveData.observeForever { observed.set(it.uid) }
            }
            subscriptionObserver = manager.activeSubscriptionFlowable(0, url).test()
            expectSubscriptionCount(0)
            manager.insertSubscription(row, info)
            expectSubscriptionCount(1)
            assertEquals(1, manager.activeSubscriptionFlowable(0, url).blockingFirst().size)
            assertEquals(1, db.subscriptionDAO().getSubscriptionFlowable(0, url).blockingFirst().size)
            manager.deleteSubscription(row)
            expectSubscriptionCount(0)
            assertTrue(manager.activeSubscriptionFlowable(0, url).blockingFirst().isEmpty())
            assertEquals(1, db.subscriptionDAO().getSubscriptionFlowable(0, url).blockingFirst().size)
            manager.insertSubscription(row, info)
            expectSubscriptionCount(1)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (observed.get() != profile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertEquals(profile, observed.get())
            ProfileSelection.select(context, FeedGroupEntity.GROUP_ALL_ID)
            val changed = SystemClock.elapsedRealtime() + 5000
            while (observed.get() != FeedGroupEntity.GROUP_ALL_ID && SystemClock.elapsedRealtime() < changed) SystemClock.sleep(25)
            assertEquals(FeedGroupEntity.GROUP_ALL_ID, observed.get())
            manager.deleteGlobalSubscription(0, url).blockingAwait()
            assertTrue(db.feedGroupDAO().getSubscriptionIdsFor(profile).blockingFirst().isEmpty())
        } finally {
            subscriptionObserver?.cancel()
            instrumentation.runOnMainSync { store.clear() }
            ProfileSelection.select(context, FeedGroupEntity.GROUP_ALL_ID)
            db.feedGroupDAO().delete(profile); db.subscriptionDAO().deleteSubscription(0, url)
        }
    }
}
