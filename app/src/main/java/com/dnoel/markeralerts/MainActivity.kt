package com.dnoel.markeralerts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.dnoel.markeralerts.data.MarkerDatabase
import com.dnoel.markeralerts.speech.Speech
import com.dnoel.markeralerts.trip.TripPreferences
import com.dnoel.markeralerts.trip.TripState
import com.dnoel.markeralerts.ui.TripScreen
import com.dnoel.markeralerts.ui.theme.MarkerAlertsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TripPreferences.load(this)
        setContent {
            MarkerAlertsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TripScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
        speakIfRequested(intent)
    }

    /**
     * The activity is `singleTop`, so a notification tapped while the app is
     * already open arrives here rather than through [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        speakIfRequested(intent)
    }

    /**
     * Reads aloud the marker whose alert was tapped.
     *
     * The distance comes from the recorded alert rather than being recomputed:
     * it is what was true when the marker spoke up, and by the time a driver
     * has tapped the notification the live distance has already changed. If the
     * marker is not in this trip's list — a cold start, or a trip that has
     * ended — it is spoken without a distance rather than with a wrong one.
     */
    private fun speakIfRequested(intent: Intent?) {
        val geomId = intent?.getStringExtra(EXTRA_SPEAK_MARKER_ID) ?: return

        // Consume it, or rotating the device would replay the same blurb.
        intent.removeExtra(EXTRA_SPEAK_MARKER_ID)

        lifecycleScope.launch {
            val marker = MarkerDatabase.build(applicationContext).markerDao().byId(geomId)
                ?: return@launch
            Speech.speak(this@MainActivity, marker)
        }
    }

    companion object {
        /** Set when the user taps an alert notification; read by [speakIfRequested]. */
        const val EXTRA_SPEAK_MARKER_ID = "speak_marker_id"
    }
}
