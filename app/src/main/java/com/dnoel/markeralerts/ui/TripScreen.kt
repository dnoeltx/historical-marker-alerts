package com.dnoel.markeralerts.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dnoel.markeralerts.speech.Speech
import com.dnoel.markeralerts.trip.TripAlert
import com.dnoel.markeralerts.trip.TripPreferences
import com.dnoel.markeralerts.trip.TripService
import com.dnoel.markeralerts.trip.TripState

/**
 * The permissions a trip needs, in one place.
 *
 * ACCESS_BACKGROUND_LOCATION is absent on purpose — see the manifest. Since
 * Android 13 notifications are also a runtime permission, and without it the
 * foreground service still runs but silently, which would look exactly like the
 * app being broken.
 */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun hasPermissions(context: Context): Boolean =
    requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun TripScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running by TripState.running.collectAsStateWithLifecycle()
    val alerts by TripState.alerts.collectAsStateWithLifecycle()
    val fixCount by TripState.fixCount.collectAsStateWithLifecycle()
    val lastFix by TripState.lastFix.collectAsStateWithLifecycle()
    val autoSpeak by TripPreferences.autoSpeak.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var denied by remember { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Location is what makes a trip possible; notifications only make it
        // audible. Treat only location as fatal.
        val hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        granted = hasLocation
        denied = !hasLocation
        if (hasLocation) TripService.start(context)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Marker Alerts", style = MaterialTheme.typography.headlineMedium)

        if (running) {
            Text(
                "Watching · $fixCount fixes",
                style = MaterialTheme.typography.bodyMedium,
            )
            lastFix?.let {
                Text(
                    "${"%.4f".format(it.lat)}, ${"%.4f".format(it.lon)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { TripService.stop(context) }) { Text("Stop trip") }
        } else {
            Text(
                "Start a trip and leave the phone alone — you'll hear about " +
                    "historical sites as you approach them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    if (granted) TripService.start(context)
                    else requestPermissions.launch(requiredPermissions())
                },
            ) { Text("Start trip") }
        }

        // Deliberately on the main screen rather than behind a settings menu:
        // this is the switch that decides whether the app can be used without
        // touching the phone, so it should be reachable before pulling out of
        // the driveway rather than discovered later.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Read aloud automatically", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (autoSpeak) "No need to touch the phone" else "Tap an alert to hear it",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = autoSpeak,
                onCheckedChange = { TripPreferences.setAutoSpeak(context, it) },
            )
        }

        if (denied) {
            Text(
                "Location permission is required to notice what you are driving past.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (alerts.isEmpty()) {
            Text(
                if (running) "Nothing yet — markers announce themselves about 3 miles out."
                else "No sites yet this trip.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(alerts) { alert ->
                    AlertCard(
                        alert = alert,
                        onSpeak = { Speech.speak(context, alert.marker, alert.distanceMeters) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: TripAlert, onSpeak: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(alert.marker.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${"%.1f".format(alert.distanceMeters / 1609.344)} mi away",
                style = MaterialTheme.typography.bodySmall,
            )
            alert.marker.blurb?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSpeak) { Text("Play") }
            }
        }
    }
}
