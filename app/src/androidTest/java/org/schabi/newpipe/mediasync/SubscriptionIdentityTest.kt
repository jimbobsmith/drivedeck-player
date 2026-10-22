package org.schabi.newpipe.mediasync

import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.subscribers.TestSubscriber
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity

/** Synthetic in-memory Room only. Run exclusively in an explicitly assigned disposable slot. */
@RunWith(AndroidJUnit4::class)
class SubscriptionIdentityTest {
    private val underscore = "https://www.youtube.com/channel/UC" + "a".repeat(10) + "_" + "b".repeat(11)
    private val letter = "https://www.youtube.com/channel/UC" + "a".repeat(10) + "X" + "b".repeat(11)
    private val differentCase = "https://www.youtube.com/channel/UC" + "a".repeat(10) + "x" + "b".repeat(11)
    private fun row(url: String, name: String, service: Int = 0) = SubscriptionEntity().apply {
        serviceId = service; this.url = url; this.name = name; avatarUrl = ""; notificationMode = 0
    }
    private fun database(block: (AppDatabase) -> Unit) {
        assertTrue("Assigned disposable emulator required", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }
    private fun expect(observer: TestSubscriber<List<SubscriptionEntity>>, ids: List<Long>) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (observer.values().lastOrNull()?.map { it.uid } != ids && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        observer.assertNoErrors()
        assertEquals(ids, observer.values().lastOrNull()?.map { it.uid })
    }
    @Test fun exactReadsDistinguishUnderscoreLetterCaseAndService() = database { db ->
        val dao = db.subscriptionDAO()
        // Insert the wildcard look-alike first so LIKE cannot accidentally return the right row.
        val other = dao.insert(row(letter, "Other channel"))
        val wanted = dao.insert(row(underscore, "Underscore channel"))
        val case = dao.insert(row(differentCase, "Case distinct channel"))
        val service = dao.insert(row(underscore, "Other service", 1))
        assertEquals(wanted, dao.getSubscription(0, underscore).blockingGet()!!.uid)
        assertEquals(other, dao.getSubscription(0, letter).blockingGet()!!.uid)
        assertEquals(case, dao.getSubscription(0, differentCase).blockingGet()!!.uid)
        assertEquals(service, dao.getSubscription(1, underscore).blockingGet()!!.uid)
        assertEquals(listOf(wanted), dao.getSubscriptionFlowable(0, underscore).blockingFirst().map { it.uid })
        assertEquals(listOf(other), dao.getSubscriptionFlowable(0, letter).blockingFirst().map { it.uid })
        assertEquals(listOf(case), dao.getSubscriptionFlowable(0, differentCase).blockingFirst().map { it.uid })
        assertEquals(listOf(service), dao.getSubscriptionFlowable(1, underscore).blockingFirst().map { it.uid })
    }
    @Test fun targetedDeleteNotifiesRetainedObserversAndKeepsLookalikesAndOtherService() = database { db ->
        val dao = db.subscriptionDAO()
        val other = dao.insert(row(letter, "Other channel"))
        val wanted = dao.insert(row(underscore, "Underscore channel"))
        val case = dao.insert(row(differentCase, "Case distinct channel"))
        val service = dao.insert(row(underscore, "Other service", 1))
        val wantedObserver = dao.getSubscriptionFlowable(0, underscore).test()
        val otherObserver = dao.getSubscriptionFlowable(0, letter).test()
        try {
            expect(wantedObserver, listOf(wanted)); expect(otherObserver, listOf(other))
            assertEquals(1, dao.deleteSubscription(0, underscore))
            expect(wantedObserver, emptyList()); expect(otherObserver, listOf(other))
            assertNull(dao.getSubscription(0, underscore).blockingGet())
            assertEquals(case, dao.getSubscription(0, differentCase).blockingGet()!!.uid)
            assertEquals(service, dao.getSubscription(1, underscore).blockingGet()!!.uid)
            assertEquals(3, dao.all.blockingFirst().size)
        } finally { wantedObserver.cancel(); otherObserver.cancel() }
    }
    @Test fun upsertFindsExactExistingUidAndDoesNotRewriteLookalikeOrOtherService() = database { db ->
        val dao = db.subscriptionDAO()
        val other = dao.insert(row(letter, "Other unchanged"))
        val wanted = dao.insert(row(underscore, "Before"))
        val service = dao.insert(row(underscore, "Service unchanged", 1))
        val changed = dao.upsertAll(listOf(row(underscore, "After"))).single()
        assertEquals(wanted, changed.uid)
        assertEquals("After", dao.getSubscription(wanted).name)
        assertEquals("Other unchanged", dao.getSubscription(other).name)
        assertEquals("Service unchanged", dao.getSubscription(service).name)
        assertEquals(3, dao.all.blockingFirst().size)
        assertEquals(setOf(wanted, other), dao.getSubscriptionsFiltered("a").blockingFirst().filter { it.serviceId == 0 }.map { it.uid }.toSet())
    }
}
