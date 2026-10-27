package org.schabi.newpipe.drivedeck

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.filter.FilterItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.ExtractorHelper
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Browse only: independent of the decoder, PlayerServiceForAuto and Room. */
class PublicYouTubeBrowserService : MediaBrowserServiceCompat() {
    private lateinit var token: MediaSessionCompat
    private val work = CompositeDisposable()
    private val pending = AtomicInteger()
    private data class Raw(val items: List<StreamInfoItem>, val next: (() -> Raw)? = null)
    private val cursors = PublicBrowsePages<List<MediaBrowserCompat.MediaItem>>(SystemClock::elapsedRealtime)

    override fun onCreate() {
        super.onCreate()
        token = MediaSessionCompat(this, "DDPublicYouTubeBrowser").apply {
            setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_NONE, 0, 0f).setActions(0).build())
            isActive = false
        }
        setSessionToken(token.sessionToken)
    }
    override fun onGetRoot(pkg: String, uid: Int, hints: Bundle?): BrowserRoot? {
        if (!allowed(pkg, uid)) return null
        return BrowserRoot(PublicYouTubePolicy.HOME, Bundle().apply {
            putString("com.drivedeck.media.presentation", PublicYouTubePolicy.PRESENTATION)
        })
    }
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val caller = currentBrowserInfo
        if (!allowed(caller.packageName, caller.uid)) { result.sendResult(null); return }
        if (pending.incrementAndGet() > 2) {
            pending.decrementAndGet(); result.sendResult(status("YouTube is busy. Retry shortly.")); return
        }
        val uid = caller.uid
        result.detach()
        val slot = SerialDisposable()
        work.add(slot)
        slot.set(Single.fromCallable {
            if (parentId.startsWith("page:")) {
                cursors.get(parentId, uid)
            } else {
                val query = PublicYouTubePolicy.query(parentId)
                project(if (query == null) home() else search(query), uid)
            }
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
            .doFinally { pending.decrementAndGet(); work.delete(slot); slot.dispose() }
            .subscribe({ items -> runCatching { result.sendResult(items.toMutableList()) } }, {
                runCatching { result.sendResult(status("Could not load YouTube. Check DD Player's connection and retry.")) }
            }))
    }
    private fun project(raw: Raw, uid: Int): List<MediaBrowserCompat.MediaItem> {
        require(raw.items.size <= PublicYouTubePolicy.MAX_SOURCE_ITEMS)
        val slice = raw.items.take(PublicYouTubePolicy.PAGE_SIZE)
        val rest = raw.items.drop(PublicYouTubePolicy.PAGE_SIZE)
        val next: (() -> Raw)? = if (rest.isNotEmpty()) ({ Raw(rest, raw.next) }) else raw.next
        val videos = slice.mapNotNull { item ->
            val id = runCatching { ServiceList.YouTube.streamLHFactory.getId(item.url) }.getOrNull()
            if (id == null || !PublicYouTubePolicy.videoId(id)) return@mapNotNull null
            val title = PublicYouTubePolicy.text(item.name)
            if (title.isEmpty()) return@mapNotNull null
            val art = PublicYouTubePolicy.thumbnail(item.thumbnailUrl)
            MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder().setMediaId("yt:$id")
                .setTitle(title).setSubtitle(PublicYouTubePolicy.text(item.uploaderName))
                .setIconUri(art?.let(android.net.Uri::parse)).build(), 0)
        }.distinctBy { it.mediaId }.toMutableList()
        if (next != null) videos += more(uid) { project(next(), uid) }
        return videos
    }
    private fun more(uid: Int, load: () -> List<MediaBrowserCompat.MediaItem>): MediaBrowserCompat.MediaItem {
        val id = cursors.add(uid, load)
        return MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder().setMediaId(id).setTitle("More videos").build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }
    private fun home(): Raw {
        val extractor = ServiceList.YouTube.kioskList.defaultKioskExtractor
        extractor.fetchPage()
        val page = extractor.initialPage
        val url = extractor.url
        return Raw(page.items.filterIsInstance<StreamInfoItem>(), if (Page.isValid(page.nextPage)) {
            { moreHome(url, page.nextPage) }
        } else null)
    }
    private fun moreHome(url: String, page: Page): Raw {
        val value = ExtractorHelper.getMoreKioskItems(0, url, page).blockingGet()
        return Raw(value.items, if (Page.isValid(value.nextPage)) { { moreHome(url, value.nextPage) } } else null)
    }
    private fun search(query: String): Raw {
        val filters = listOfNotNull(ServiceList.YouTube.searchQHFactory.getFilterItem(0))
        val info = ExtractorHelper.searchFor(0, query, filters, emptyList()).blockingGet()
        return Raw(info.relatedItems.filterIsInstance<StreamInfoItem>(), if (Page.isValid(info.nextPage)) {
            { moreSearch(query, filters, info.nextPage) }
        } else null)
    }
    private fun moreSearch(query: String, filters: List<FilterItem>, page: Page): Raw {
        val value = ExtractorHelper.getMoreSearchItems(0, query, filters, emptyList(), page).blockingGet()
        return Raw(value.items.filterIsInstance<StreamInfoItem>(), if (Page.isValid(value.nextPage)) {
            { moreSearch(query, filters, value.nextPage) }
        } else null)
    }
    private fun status(message: String) = mutableListOf(MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId("status").setTitle(message).build(), 0))
    private fun allowed(pkg: String, uid: Int): Boolean = runCatching {
        PublicYouTubePolicy.allowedCaller(pkg, packageManager.getPackagesForUid(uid).orEmpty().toSet(),
            signers(pkg), signers(packageName), BuildConfig.DEBUG, uid == Process.myUid())
    }.getOrDefault(false)
    @Suppress("DEPRECATION")
    private fun signers(pkg: String): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = packageManager.getPackageInfo(pkg, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        return signatures.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
    override fun onDestroy() {
        work.dispose()
        cursors.close()
        token.release()
        super.onDestroy()
    }
}
