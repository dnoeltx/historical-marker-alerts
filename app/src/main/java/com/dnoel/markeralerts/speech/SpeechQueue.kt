package com.dnoel.markeralerts.speech

/**
 * Speaks markers one at a time, in order, holding audio focus for the whole run.
 *
 * ## Why this exists
 *
 * M3's route replay showed alerts landing as little as 500 m apart — about 16
 * seconds at highway speed, and a 600-character blurb takes longer than that to
 * read. Three ways to handle a second alert arriving mid-sentence:
 *
 *  - interrupt the first (`QUEUE_FLUSH`): the driver hears two half-sentences
 *  - drop the second: the detector starts deciding what is worth knowing based
 *    on how busy the speaker is, which is the wrong reason
 *  - **queue it**, which is what this does
 *
 * ## Focus is held across the run, not per utterance
 *
 * Requesting focus per utterance makes music duck and un-duck between markers,
 * which is worse than either state. Focus is taken when the queue starts
 * flowing and abandoned when it drains.
 *
 * ## Queued speech goes stale
 *
 * Queueing without a time limit has its own failure: through a dense town the
 * backlog grows, and the driver ends up hearing about a marker from ten minutes
 * and eight miles ago. An utterance that has waited longer than [maxWaitMillis]
 * is dropped when it reaches the front. Ninety seconds is roughly 1.7 miles at
 * 70 mph — past the point where "coming up" is true.
 *
 * Not thread-safe: it is driven from the service's main-thread callbacks.
 */
class SpeechQueue(
    private val engine: SpeechEngine,
    private val focus: AudioFocusGate,
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class Pending(val id: String, val text: String, val queuedAt: Long)

    private val pending = ArrayDeque<Pending>()
    private var speaking: String? = null
    private var holdsFocus = false
    private var droppedCount = 0

    /**
     * Adds a marker to the queue. Re-enqueueing something already speaking or
     * already waiting is ignored, so a duplicate alert cannot cause a stutter.
     */
    fun enqueue(id: String, text: String) {
        if (id == speaking || pending.any { it.id == id }) return
        pending.addLast(Pending(id, text, now()))
        pump()
    }

    /** Called by the engine when an utterance finishes or fails. */
    fun onDone(id: String) {
        if (id != speaking) return
        speaking = null
        pump()
    }

    /**
     * The system took focus away — a call, or a navigation prompt that asked to
     * interrupt rather than duck. Give up the whole backlog rather than resuming
     * into the middle of a sentence afterwards.
     */
    fun onFocusLost() {
        engine.stop()
        droppedCount += pending.size
        pending.clear()
        speaking = null
        holdsFocus = false
    }

    /** End of trip, or the user silenced it. */
    fun clear() {
        engine.stop()
        pending.clear()
        speaking = null
        releaseFocus()
    }

    val isSpeaking: Boolean get() = speaking != null
    val queuedCount: Int get() = pending.size
    val dropped: Int get() = droppedCount

    private fun pump() {
        if (speaking != null) return

        discardStale()

        if (pending.isEmpty()) {
            releaseFocus()
            return
        }

        if (!holdsFocus) {
            holdsFocus = focus.request()
            if (!holdsFocus) {
                // Something with a stronger claim is using the speaker. Speaking
                // over a phone call is worse than silence, and a backlog dumped
                // out when the call ends is worse still.
                droppedCount += pending.size
                pending.clear()
                return
            }
        }

        val next = pending.removeFirst()
        speaking = next.id
        engine.speak(next.id, next.text)
    }

    private fun discardStale() {
        val cutoff = now() - maxWaitMillis
        while (pending.isNotEmpty() && pending.first().queuedAt < cutoff) {
            pending.removeFirst()
            droppedCount += 1
        }
    }

    private fun releaseFocus() {
        if (!holdsFocus) return
        focus.abandon()
        holdsFocus = false
    }

    companion object {
        const val DEFAULT_MAX_WAIT_MILLIS = 90_000L
    }
}
