package org.schabi.newpipe.local.subscription

import android.content.Context
import androidx.preference.PreferenceManager
import android.content.SharedPreferences
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.feed.model.FeedGroupEntity

/** Device-local active profile, shared by subscription actions, channel list and every feed tab. */
object ProfileSelection {
    const val KEY = "dd_active_profile"
    @JvmStatic fun current(context: Context) = PreferenceManager.getDefaultSharedPreferences(context).getLong(KEY, FeedGroupEntity.GROUP_ALL_ID)
    @JvmStatic fun select(context: Context, id: Long) { PreferenceManager.getDefaultSharedPreferences(context).edit().putLong(KEY, id).apply() }
    fun observe(context: Context, db: AppDatabase): Flowable<FeedGroupEntity> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val selected = Flowable.create<Long>({ emitter ->
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY && !emitter.isCancelled) emitter.onNext(current(context))
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            emitter.setCancellable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            emitter.onNext(current(context))
        }, BackpressureStrategy.LATEST)
        return Flowable.combineLatest(selected, db.feedGroupDAO().getAll()) { id, groups ->
            groups.firstOrNull { it.uid == id } ?: FeedGroupEntity(FeedGroupEntity.GROUP_ALL_ID, "", FeedGroupIcon.ALL).also {
                if (id != FeedGroupEntity.GROUP_ALL_ID) select(context, FeedGroupEntity.GROUP_ALL_ID)
            }
        }.distinctUntilChanged()
    }
}
