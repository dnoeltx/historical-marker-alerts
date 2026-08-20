package com.dnoel.markeralerts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.dnoel.markeralerts.ui.TripScreen
import com.dnoel.markeralerts.ui.theme.MarkerAlertsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkerAlertsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TripScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    companion object {
        /**
         * Set when the user taps an alert notification. M4 reads it to speak the
         * blurb aloud; until then the app simply comes to the foreground.
         */
        const val EXTRA_SPEAK_MARKER_ID = "speak_marker_id"
    }
}
