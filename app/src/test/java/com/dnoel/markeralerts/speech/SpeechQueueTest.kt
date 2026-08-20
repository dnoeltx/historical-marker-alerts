package com.dnoel.markeralerts.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a driver actually hears.
 *
 * All of this is pure Kotlin: the platform's TextToSpeech and AudioManager sit
 * behind [SpeechEngine] and [AudioFocusGate], so the ordering, the staleness
 * cutoff and the focus lifecycle can be exercised on the JVM in milliseconds.
 */
class SpeechQueueTest {

    private class FakeEngine : SpeechEngine {
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        override fun speak(id: String, text: String) {
            spoken += id to text
        }
        override fun stop() {
            stops += 1
        }
    }

    private class FakeFocus(var granted: Boolean = true) : AudioFocusGate {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean {
            requests += 1
            return granted
        }
        override fun abandon() {
            abandons += 1
        }
    }

    private var clock = 0L

    private fun queue(
        engine: FakeEngine = FakeEngine(),
        focus: FakeFocus = FakeFocus(),
        maxWaitMillis: Long = SpeechQueue.DEFAULT_MAX_WAIT_MILLIS,
        // Named, not a trailing lambda. When `onSpeakingChanged` was added as
        // the last parameter, a trailing `{ clock }` here silently re-bound to
        // it and `now` fell back to the real system clock — the fake clock
        // stopped working and the staleness tests passed for the wrong reason.
        // Nothing about that is a compile error, so name the argument.
    ) = SpeechQueue(engine, focus, maxWaitMillis, now = { clock })

    @Test
    fun `a second marker waits its turn instead of interrupting`() {
        val engine = FakeEngine()
        val q = queue(engine)

        q.enqueue("a", "first")
        q.enqueue("b", "second")

        // This is the whole point of the milestone: the second alert does not
        // cut the first one off mid-sentence.
        assertEquals(listOf("a" to "first"), engine.spoken)
        assertEquals(1, q.queuedCount)

        q.onDone("a")
        assertEquals(listOf("a" to "first", "b" to "second"), engine.spoken)
        assertEquals(0, q.queuedCount)
    }

    @Test
    fun `focus is held once across a run, not per utterance`() {
        val focus = FakeFocus()
        val q = queue(focus = focus)

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.onDone("a")

        // Requesting per utterance would make music duck, un-duck and duck
        // again between two markers a few seconds apart.
        assertEquals(1, focus.requests)
        assertEquals(0, focus.abandons)

        q.onDone("b")
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `focus is released when the queue drains`() {
        val focus = FakeFocus()
        val q = queue(focus = focus)

        q.enqueue("a", "only")
        q.onDone("a")

        assertEquals(1, focus.abandons)
        assertFalse(q.isSpeaking)
    }

    @Test
    fun `a later run takes focus again`() {
        val focus = FakeFocus()
        val q = queue(focus = focus)

        q.enqueue("a", "first")
        q.onDone("a")
        q.enqueue("b", "second")
        q.onDone("b")

        assertEquals(2, focus.requests)
        assertEquals(2, focus.abandons)
    }

    @Test
    fun `nothing is spoken when focus is refused`() {
        val engine = FakeEngine()
        val q = queue(engine, FakeFocus(granted = false))

        q.enqueue("a", "during a phone call")

        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, q.queuedCount)
        assertEquals(1, q.dropped)
    }

    @Test
    fun `a backlog is abandoned rather than replayed after an interruption`() {
        val engine = FakeEngine()
        val q = queue(engine)

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.enqueue("c", "third")

        q.onFocusLost()

        // Resuming into the middle of a sentence after a call would be worse
        // than saying nothing.
        assertEquals(1, engine.stops)
        assertEquals(0, q.queuedCount)
        assertFalse(q.isSpeaking)
        assertEquals(2, q.dropped)
    }

    @Test
    fun `an utterance that waited too long is dropped, not spoken late`() {
        val engine = FakeEngine()
        val q = queue(engine, maxWaitMillis = 90_000L)

        q.enqueue("a", "speaking now")
        q.enqueue("b", "queued behind it")

        // A very long first blurb: by the time it finishes, the second marker
        // is miles behind and "coming up" is no longer true.
        clock += 91_000L
        q.onDone("a")

        assertEquals(listOf("a" to "speaking now"), engine.spoken)
        assertEquals(1, q.dropped)
        assertFalse(q.isSpeaking)
    }

    @Test
    fun `an utterance still inside the window is spoken`() {
        val engine = FakeEngine()
        val q = queue(engine, maxWaitMillis = 90_000L)

        q.enqueue("a", "speaking now")
        q.enqueue("b", "queued behind it")

        clock += 89_000L
        q.onDone("a")

        // Boundary partner to the test above: this is what proves the cutoff is
        // a cutoff rather than "drop everything queued".
        assertEquals(2, engine.spoken.size)
        assertEquals(0, q.dropped)
    }

    @Test
    fun `the same marker twice does not stutter`() {
        val engine = FakeEngine()
        val q = queue(engine)

        q.enqueue("a", "text")
        q.enqueue("a", "text")
        q.onDone("a")

        assertEquals(1, engine.spoken.size)
    }

    @Test
    fun `a marker already waiting is not queued twice`() {
        val engine = FakeEngine()
        val q = queue(engine)

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.enqueue("b", "second again")

        assertEquals(1, q.queuedCount)
    }

    @Test
    fun `clearing stops speech and gives focus back`() {
        val engine = FakeEngine()
        val focus = FakeFocus()
        val q = queue(engine, focus)

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.clear()

        assertEquals(1, engine.stops)
        assertEquals(1, focus.abandons)
        assertEquals(0, q.queuedCount)
        assertFalse(q.isSpeaking)
    }

    @Test
    fun `clearing an idle queue does not abandon focus it never held`() {
        val focus = FakeFocus()
        val q = queue(focus = focus)

        q.clear()

        assertEquals(0, focus.abandons)
    }

    @Test
    fun `the speaking id is reported as it changes`() {
        val seen = mutableListOf<String?>()
        val engine = FakeEngine()
        val q = SpeechQueue(engine, FakeFocus(), SpeechQueue.DEFAULT_MAX_WAIT_MILLIS, { clock }) {
            seen += it
        }

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.onDone("a")
        q.onDone("b")

        // The null between "a" and "b" is real — speech genuinely stopped
        // before the next utterance began. Both writes happen synchronously
        // inside onDone, so the StateFlow they drive conflates them and the UI
        // never renders the gap.
        assertEquals(listOf("a", null, "b", null), seen)
    }

    @Test
    fun `the speaking id clears when speech is stopped`() {
        val seen = mutableListOf<String?>()
        val q = SpeechQueue(FakeEngine(), FakeFocus(), SpeechQueue.DEFAULT_MAX_WAIT_MILLIS, { clock }) {
            seen += it
        }

        q.enqueue("a", "first")
        q.clear()

        assertEquals(listOf("a", null), seen)
    }

    @Test
    fun `the speaking id is not reported twice for the same value`() {
        val seen = mutableListOf<String?>()
        val q = SpeechQueue(FakeEngine(), FakeFocus(), SpeechQueue.DEFAULT_MAX_WAIT_MILLIS, { clock }) {
            seen += it
        }

        q.enqueue("a", "first")
        q.onDone("a")
        // Nothing queued behind it, so this drains to null exactly once — a
        // repeat would make a StateFlow emit spuriously.
        q.clear()

        assertEquals(listOf("a", null), seen)
    }

    @Test
    fun `a completion for something else is ignored`() {
        val engine = FakeEngine()
        val q = queue(engine)

        q.enqueue("a", "first")
        q.enqueue("b", "second")
        q.onDone("stale-id")

        // Still on the first utterance; a stray callback must not advance it.
        assertTrue(q.isSpeaking)
        assertEquals(1, engine.spoken.size)
    }
}
