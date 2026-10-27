package org.schabi.newpipe.drivedeck

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Public metadata only. Client-supplied URLs are never fetched. */
internal object PublicYouTubePolicy {
    const val PRESENTATION = "youtube-public-v1"
    const val HOME = "home"
    const val PAGE_SIZE = 23
    const val MAX_SOURCE_ITEMS = 500
    private const val DD = "com.drivedeck.g"
    private val official = setOf("29d743afecc02f012121ad06759abac5fcab5812c5d0af01a4258346495f05c0")
    fun allowedCaller(pkg: String, packages: Set<String>, signers: Set<String>,
                      ownSigners: Set<String>, debug: Boolean, sameUid: Boolean): Boolean {
        if (pkg !in packages) return false
        if (sameUid) return true
        if (pkg == DD && signers == official) return true
        return debug && pkg in setOf(DD, "$DD.test") && signers.isNotEmpty() && signers == ownSigners
    }
    fun query(parent: String): String? {
        if (parent == HOME) return null
        require(parent.startsWith("search:") && parent.length <= 2400)
        val value = URLDecoder.decode(parent.substring(7), "UTF-8").trim()
        require(value.length in 1..200 && value.none(Char::isISOControl))
        return value
    }
    fun videoId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{11}"))
    fun thumbnail(value: String?): String? = runCatching {
        val uri = URI(value ?: return null)
        value.takeIf { uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in
            setOf("i.ytimg.com", "img.youtube.com") && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && value.length <= 2048 }
    }.getOrNull()
    fun text(value: String?) = value.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(256)
}
