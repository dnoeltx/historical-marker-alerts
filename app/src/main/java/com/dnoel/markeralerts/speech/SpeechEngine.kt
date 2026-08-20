package com.dnoel.markeralerts.speech

/**
 * The two Android capabilities speech needs, reduced to what [SpeechQueue]
 * actually uses.
 *
 * Narrow interfaces rather than passing TextToSpeech and AudioManager around:
 * the queue's rules — ordering, staleness, when focus is held — are the part
 * worth testing, and neither real class can be exercised on the JVM.
 */
interface SpeechEngine {
    /** Begins speaking. [id] comes back through the done callback. */
    fun speak(id: String, text: String)

    /** Abandons anything in progress immediately. */
    fun stop()
}

interface AudioFocusGate {
    /** Ducks other audio. False means something refused to yield — a call. */
    fun request(): Boolean

    /** Restores other audio to full volume. */
    fun abandon()
}
