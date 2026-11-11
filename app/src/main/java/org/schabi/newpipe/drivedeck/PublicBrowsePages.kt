package org.schabi.newpipe.drivedeck

import java.io.Closeable
import java.util.UUID

/** Opaque, caller-owned, short-lived continuations. Provider Page objects stay in process. */
internal class PublicBrowsePages<T>(
    private val now: () -> Long,
    private val capacity: Int = 16,
    private val lifetimeMs: Long = 180_000
) : Closeable {
    private class Once<T>(private val load: () -> T) {
        private var cached: T? = null
        @Synchronized fun get(): T = cached ?: load().also { cached = it }
    }
    private data class Entry<T>(val uid: Int, val expires: Long, val page: Once<T>, val library: Boolean)
    private val entries = linkedMapOf<String, Entry<T>>()
    private var closed = false

    /** [library] marks a continuation of the owner's library, which [dropPrivate] revokes. */
    @Synchronized fun add(uid: Int, library: Boolean = false, load: () -> T): String {
        check(!closed)
        val time = now()
        entries.entries.removeAll { it.value.expires <= time }
        while (entries.size >= capacity) entries.remove(entries.keys.first())
        val id = "page:${UUID.randomUUID()}"
        entries[id] = Entry(uid, time + lifetimeMs, Once(load), library)
        return id
    }
    fun get(id: String, uid: Int): T {
        val entry = synchronized(this) {
            check(!closed)
            requireNotNull(entries[id]).also { require(it.uid == uid && it.expires > now()) }
        }
        val value = entry.page.get()
        synchronized(this) { check(!closed); require(entry.expires > now()) }
        return value
    }
    @Synchronized fun isPrivate(id: String) = entries[id]?.library == true
    /** Forgets every library continuation, loaded or not, so none can be read again. */
    @Synchronized fun dropPrivate() { entries.entries.removeAll { it.value.library } }
    @Synchronized override fun close() { closed = true; entries.clear() }
}
