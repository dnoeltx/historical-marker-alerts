package com.dnoel.markeralerts.speech

import android.content.Context
import com.dnoel.markeralerts.data.MarkerEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide access to speech, for the same reason [TripState] is an object:
 * dependency injection has not arrived yet, and two callers need the same
 * instance. The service speaks automatically during a trip; the activity speaks
 * when an alert notification is tapped. Two TextToSpeech engines would fight
 * over the same audio focus.
 *
 * The engine is deliberately **not** shut down when a trip ends. Starting
 * TextToSpeech takes on the order of a second, and paying that on the first
 * marker of a trip would clip the beginning of the sentence. An idle engine
 * costs a service binding; the process going away releases it.
 */
object Speech {

    private var engine: AndroidSpeechEngine? = null
    private var queue: SpeechQueue? = null

    private val _speakingId = MutableStateFlow<String?>(null)

    /**
     * The geomId currently being read aloud, or null.
     *
     * Lets a list entry show "Stop" while it is the one talking. Lives here
     * rather than on [SpeechQueue] so the queue stays free of coroutines and
     * testable as plain Kotlin.
     */
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    @Synchronized
    private fun ensure(context: Context): SpeechQueue {
        queue?.let { return it }

        // The engine reports completions to the queue and the focus gate reports
        // interruptions to it, but the queue needs both to exist first. A holder
        // read at callback time rather than at construction time unties the knot.
        val created = AndroidSpeechEngine(context) { id -> queue?.onDone(id) }
        val gate = AndroidAudioFocusGate(context) { queue?.onFocusLost() }

        val made = SpeechQueue(created, gate) { id -> _speakingId.value = id }
        engine = created
        queue = made
        return made
    }

    /** Reads a marker aloud, behind anything already speaking. */
    fun speak(context: Context, marker: MarkerEntity) {
        ensure(context).enqueue(
            id = marker.geomId,
            text = Utterance.forMarker(marker),
        )
    }

    /** Stops immediately and abandons the backlog — trip over, or silenced. */
    @Synchronized
    fun clear() {
        queue?.clear()
    }

    /** Test and teardown seam. */
    @Synchronized
    fun shutdown() {
        queue?.clear()
        engine?.shutdown()
        engine = null
        queue = null
    }
}
