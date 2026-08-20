package com.dnoel.markeralerts.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Ducks other audio while a marker is being read out.
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` is the polite request: music drops in
 * volume for a few seconds and comes back on its own. Asking for a full
 * `AUDIOFOCUS_GAIN` would pause Spotify outright and leave it paused, which is
 * a genuinely annoying thing to do to somebody four hours into a drive.
 *
 * [onLost] fires when something with a stronger claim — an incoming call —
 * takes the speaker away.
 */
class AndroidAudioFocusGate(
    context: Context,
    private val onLost: () -> Unit,
) : AudioFocusGate {

    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val request =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> onLost()
                }
            }
            .build()

    override fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
