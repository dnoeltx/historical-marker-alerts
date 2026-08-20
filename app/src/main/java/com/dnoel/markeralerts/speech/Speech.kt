package com.dnoel.markeralerts.speech

import android.content.Context
import com.dnoel.markeralerts.data.MarkerEntity

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

    @Synchronized
    private fun ensure(context: Context): SpeechQueue {
        queue?.let { return it }

        // The engine reports completions to the queue and the focus gate reports
        // interruptions to it, but the queue needs both to exist first. A holder
        // read at callback time rather than at construction time unties the knot.
        val created = AndroidSpeechEngine(context) { id -> queue?.onDone(id) }
        val gate = AndroidAudioFocusGate(context) { queue?.onFocusLost() }

        val made = SpeechQueue(created, gate)
        engine = created
        queue = made
        return made
    }

    /** Reads a marker aloud, behind anything already speaking. */
    fun speak(context: Context, marker: MarkerEntity, distanceMeters: Double?) {
        ensure(context).enqueue(
            id = marker.geomId,
            text = Utterance.forMarker(marker, distanceMeters),
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
