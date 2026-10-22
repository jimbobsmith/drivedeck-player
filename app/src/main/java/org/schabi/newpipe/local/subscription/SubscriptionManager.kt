package org.schabi.newpipe.local.subscription

import android.content.Context
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.feed.model.FeedGroupSubscriptionEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.database.subscription.SubscriptionDAO
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.feed.service.FeedUpdateInfo
import org.schabi.newpipe.util.ExtractorHelper

class SubscriptionManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(context)
    private val subscriptionTable = database.subscriptionDAO()
    private val feedDatabaseManager = FeedDatabaseManager(context)
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private fun activeProfile() = preferences.getLong("dd_active_profile", FeedGroupEntity.GROUP_ALL_ID)
    fun activeSubscriptionFlowable(serviceId: Int, url: String): Flowable<List<SubscriptionEntity>> {
        return ProfileSelection.observe(applicationContext, database).switchMap { profile ->
            if (profile.uid == FeedGroupEntity.GROUP_ALL_ID) subscriptionTable.getSubscriptionFlowable(serviceId, url)
            else subscriptionTable.getProfileSubscriptionFlowable(profile.uid, serviceId, url)
        }
    }
    private fun validProfile(): Long {
        val id = activeProfile()
        return if (id > 0 && database.query("SELECT uid FROM feed_group WHERE uid=?", arrayOf(id)).use { it.moveToFirst() }) id else FeedGroupEntity.GROUP_ALL_ID
    }
    private fun removeFromActiveProfile(serviceId: Int, url: String) {
        val profile = validProfile()
        if (profile == FeedGroupEntity.GROUP_ALL_ID) subscriptionTable.deleteSubscription(serviceId, url)
        else database.openHelper.writableDatabase.execSQL("DELETE FROM feed_group_subscription_join WHERE group_id=? AND subscription_id IN (SELECT uid FROM subscriptions WHERE service_id=? AND url=?)", arrayOf<Any>(profile, serviceId, url))
    }

    fun subscriptionTable(): SubscriptionDAO = subscriptionTable
    fun subscriptions() = subscriptionTable.all

    fun getSubscriptions(
        currentGroupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        filterQuery: String = "",
        showOnlyUngrouped: Boolean = false
    ): Flowable<List<SubscriptionEntity>> {
        return when {
            filterQuery.isNotEmpty() -> {
                return if (showOnlyUngrouped) {
                    subscriptionTable.getSubscriptionsOnlyUngroupedFiltered(
                        currentGroupId, filterQuery
                    )
                } else {
                    if (currentGroupId != FeedGroupEntity.GROUP_ALL_ID) {
                        subscriptionTable.getSubscriptionsForCurrentGroupFiltered(currentGroupId, filterQuery)
                    }
                    else subscriptionTable.getSubscriptionsFiltered(filterQuery)
                }
            }
            showOnlyUngrouped -> subscriptionTable.getSubscriptionsOnlyUngrouped(currentGroupId)
            currentGroupId != FeedGroupEntity.GROUP_ALL_ID -> subscriptionTable.getSubscriptionsForCurrentGroup(currentGroupId)
            else -> subscriptionTable.all
        }
    }

    fun upsertAll(infoList: List<ChannelInfo>): List<SubscriptionEntity> {
        val listEntities = subscriptionTable.upsertAll(
            infoList.map { SubscriptionEntity.from(it) }
        )

        database.runInTransaction {
            infoList.forEachIndexed { index, info ->
                feedDatabaseManager.upsertAll(listEntities[index].uid, info.relatedItems)
            }
        }

        return listEntities
    }

    fun insertAll(subscriptionItems: List<SubscriptionItem>): List<SubscriptionEntity> =
        subscriptionTable.insertAllIgnoringExisting(
            subscriptionItems.map { SubscriptionEntity.from(it) }
        )

    fun updateChannelInfo(subscriptionId: Long, info: ChannelInfo) {
        val subscriptionEntity = subscriptionTable.getSubscription(subscriptionId)
        if (info.name == null) return

        subscriptionEntity.setData(
            info.name,
            info.avatarUrl,
            info.description,
            info.subscriberCount
        )
        subscriptionTable.update(subscriptionEntity)
        feedDatabaseManager.upsertAll(subscriptionId, info.relatedItems)
    }

    fun updateChannelInfo(info: ChannelInfo): Completable = subscriptionTable.getSubscription(info.serviceId, info.url)
        .flatMapCompletable {
            Completable.fromRunnable {
                if (info.name == null) return@fromRunnable
                it.setData(info.name, info.avatarUrl, info.description, info.subscriberCount)
                subscriptionTable.update(it)
                feedDatabaseManager.upsertAll(it.uid, info.relatedItems)
            }
        }

    fun updateNotificationMode(serviceId: Int, url: String, @NotificationMode mode: Int): Completable {
        return subscriptionTable().getSubscription(serviceId, url)
            .flatMapCompletable { entity: SubscriptionEntity ->
                Completable.fromAction {
                    entity.notificationMode = mode
                    subscriptionTable().update(entity)
                }.apply {
                    if (mode != NotificationMode.DISABLED) {
                        // notifications have just been enabled, mark all streams as "old"
                        andThen(rememberAllStreams(entity))
                    }
                }
            }
    }

    fun updateFromInfo(info: FeedUpdateInfo) {
        val subscriptionEntity = subscriptionTable.getSubscription(info.uid)

        subscriptionEntity.name = info.name

        // some services do not provide an avatar URL
        info.avatarUrl?.let { subscriptionEntity.avatarUrl = it }

        // these two fields are null if the feed info was fetched using the fast feed method
        info.description?.let { subscriptionEntity.description = it }
        info.subscriberCount?.let { subscriptionEntity.subscriberCount = it }

        subscriptionTable.update(subscriptionEntity)
    }

    fun deleteSubscription(serviceId: Int, url: String): Completable {
        return Completable.fromAction { removeFromActiveProfile(serviceId, url) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    /** Explicit global unsubscribe in the profile channel menu, distinct from removing membership. */
    fun deleteGlobalSubscription(serviceId: Int, url: String): Completable = Completable.fromAction {
        subscriptionTable.deleteSubscription(serviceId, url)
    }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())

    fun deleteAllSubscriptions(): Completable {
        return Completable.fromAction { subscriptionTable.deleteAll() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun insertSubscription(subscriptionEntity: SubscriptionEntity, info: ChannelInfo) {
        database.runInTransaction {
            val existing = database.query("SELECT uid FROM subscriptions WHERE service_id=? AND url=?", arrayOf(subscriptionEntity.serviceId, subscriptionEntity.url)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            val subscriptionId = if (existing > 0) existing else subscriptionTable.insert(subscriptionEntity)
            val profile = validProfile()
            if (profile > 0) database.feedGroupDAO().insertSubscriptionsToGroup(listOf(FeedGroupSubscriptionEntity(profile, subscriptionId)))
            feedDatabaseManager.upsertAll(subscriptionId, info.relatedItems)
        }
    }

    fun deleteSubscription(subscriptionEntity: SubscriptionEntity) {
        removeFromActiveProfile(subscriptionEntity.serviceId, subscriptionEntity.url)
    }

    /**
     * Fetches the list of videos for the provided channel and saves them in the database, so that
     * they will be considered as "old"/"already seen" streams and the user will never be notified
     * about any one of them.
     */
    private fun rememberAllStreams(subscription: SubscriptionEntity): Completable {
        return ExtractorHelper.getChannelInfo(subscription.serviceId, subscription.url, false)
            .map { channel -> channel.relatedItems.map { stream -> StreamEntity(stream) } }
            .flatMapCompletable { entities ->
                Completable.fromAction {
                    database.streamDAO().upsertAll(entities)
                }
            }.onErrorComplete()
    }
}
