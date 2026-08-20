package com.dnoel.markeralerts.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * [SpeechEngine] backed by the platform TextToSpeech engine.
 *
 * Two things here are easy to get wrong and both are about being a good citizen
 * of the car's audio:
 *
 *  - `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` makes this sound like a navigation
 *    prompt to the rest of the system, which is exactly what it is. Bluetooth
 *    head units route it correctly and music apps duck rather than pause.
 *  - Every failure path must still report done. TextToSpeech can fail to
 *    initialise, or fail mid-utterance; if either silently skips the callback,
 *    [SpeechQueue] waits forever and the trip goes mute with a full queue.
 */
class AndroidSpeechEngine(
    context: Context,
    private val onDone: (String) -> Unit,
) : SpeechEngine {

    private var ready = false
    private var failed = false

    /** Set when speak() is called before the engine finishes starting up. */
    private var deferred: Pair<String, String>? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            deferred?.let { (id, text) -> deferred = null; startSpeaking(id, text) }
        } else {
            // No engine, or it refused to start. Drain rather than stall.
            failed = true
            deferred?.let { (id, _) -> deferred = null; onDone(id) }
        }
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(this@AndroidSpeechEngine.onDone)
            }

            @Deprecated("Required override; the int-code overload replaces it.")
            override fun onError(utteranceId: String?) {
                utteranceId?.let(this@AndroidSpeechEngine.onDone)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let(this@AndroidSpeechEngine.onDone)
            }
        })
    }

    override fun speak(id: String, text: String) {
        when {
            failed -> onDone(id)
            ready -> startSpeaking(id, text)
            // Still initialising. Only the most recent matters: the queue only
            // ever has one utterance in flight.
            else -> deferred = id to text
        }
    }

    private fun startSpeaking(id: String, text: String) {
        // QUEUE_FLUSH, not QUEUE_ADD: SpeechQueue owns the ordering, and this
        // engine is only ever asked for one utterance at a time. Letting
        // TextToSpeech keep its own parallel queue would mean two queues with
        // different staleness rules.
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result == TextToSpeech.ERROR) onDone(id)
    }

    override fun stop() {
        if (ready) tts.stop()
        deferred = null
    }

    /** Releases the engine's resources. After this the instance is unusable. */
    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
