package org.schabi.newpipe.drivedeck

import org.schabi.newpipe.extractor.exceptions.AntiBotException

/**
 * What DD PipePipe does when YouTube blocks the address it plays from. Pure rules, kept apart
 * from the player and the Proton request so they can be tested on the JVM.
 */
object ProtonBlockPolicy {

    enum class Mode(val key: String) {
        AUTOMATIC("automatic"),
        ASK("ask"),
        OFF("off");

        companion object {
            fun of(value: String?): Mode = entries.firstOrNull { it.key == value } ?: AUTOMATIC
        }
    }

    enum class Block { IP_BLOCK, RATE_LIMIT, STREAM_403 }

    enum class Decision { NONE, SWITCH, ASK, PAUSED }

    private const val MAX_CAUSE_DEPTH = 12

    /**
     * The block a failure shows, or null. YouTube's bot check (the extractor's
     * [AntiBotException]) and HTTP 403/429 on a stream count. Other failures do not: a new
     * address would not fix them.
     */
    fun classify(error: Throwable?): Block? {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is AntiBotException) return Block.IP_BLOCK
            val message = current.message
            if (message != null) {
                if (message.contains("Sign in to confirm")) return Block.IP_BLOCK
                if (message.contains("Response code: 429")) return Block.RATE_LIMIT
                if (message.contains("Response code: 403")) return Block.STREAM_403
            }
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * Limits for automatic switches, so a block a new address cannot fix does not keep switching
     * servers. A switch the user asks for is never limited.
     */
    class Budget(
        private val perItem: Int = 2,
        private val perWindow: Int = 4,
        private val windowMs: Long = 15 * 60_000L,
        private val now: () -> Long = System::currentTimeMillis,
    ) {
        private val recent = ArrayDeque<Pair<String, Long>>()

        @Synchronized
        fun allows(item: String): Boolean {
            prune()
            return recent.count { it.first == item } < perItem && recent.size < perWindow
        }

        @Synchronized
        fun record(item: String) {
            recent.addLast(item to now())
        }

        @Synchronized
        fun count(): Int {
            prune()
            return recent.size
        }

        private fun prune() {
            val since = now() - windowMs
            while (recent.isNotEmpty() && recent.first().second < since) recent.removeFirst()
        }
    }

    fun decide(mode: Mode, protonReady: Boolean, budgetAllows: Boolean, canAsk: Boolean): Decision = when {
        mode == Mode.OFF || !protonReady -> Decision.NONE
        mode == Mode.ASK -> if (canAsk) Decision.ASK else Decision.NONE
        budgetAllows -> Decision.SWITCH
        else -> Decision.PAUSED
    }
}
