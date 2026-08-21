package com.dnoel.markeralerts.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dnoel.markeralerts.data.MarkerEntity
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

/**
 * Shows a site on whatever map app the device has. Returns false if it has
 * none — a bare `startActivity` would throw `ActivityNotFoundException` and
 * take the app down, which is a poor trade for a convenience button.
 */
private fun openMap(context: Context, marker: MarkerEntity): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, MapLink.forMarker(marker).toUri())
            // The card is inside an Activity, but the flag costs nothing and
            // keeps this correct if it is ever called from a non-Activity
            // context such as a notification action.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Composable
fun TripScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running by TripState.running.collectAsStateWithLifecycle()
    val alerts by TripState.alerts.collectAsStateWithLifecycle()
    val fixCount by TripState.fixCount.collectAsStateWithLifecycle()
    val lastFix by TripState.lastFix.collectAsStateWithLifecycle()
    val autoSpeak by TripPreferences.autoSpeak.collectAsStateWithLifecycle()
    val radiusMiles by TripPreferences.radiusMiles.collectAsStateWithLifecycle()
    val announceAtStart by TripPreferences.announceAtStart.collectAsStateWithLifecycle()
    val speakingId by Speech.speakingId.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var denied by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Stops the current blurb and drops the backlog without ending
                // the trip. Harmless to press when nothing is speaking, so it
                // needs no is-speaking state to drive its enabled-ness.
                OutlinedButton(onClick = { Speech.clear() }) { Text("Silence") }
                OutlinedButton(onClick = { TripService.stop(context) }) { Text("Stop trip") }
            }
            Text(
                "Silence is also on the notification, so you can reach it " +
                    "without unlocking.",
                style = MaterialTheme.typography.bodySmall,
            )
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

        // Both settings below are read once when a trip starts, so they are
        // hidden while one is running rather than offering a control that
        // would silently do nothing.
        if (!running) {
            HorizontalDivider()

            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Alert distance", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "How far ahead a site announces itself. " +
                        "3 miles is about 2½ minutes at 70 mph.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TripPreferences.RADIUS_CHOICES_MILES.forEach { miles ->
                        val selected = radiusMiles == miles
                        FilterChip(
                            selected = selected,
                            onClick = { TripPreferences.setRadiusMiles(context, miles) },
                            label = { Text("${miles.toInt()} mi") },
                            // Without an explicit icon the selected chip is only
                            // a shade lighter than the others — legible up close,
                            // invisible at a glance in a car. A checkmark makes
                            // the state readable without comparing chips.
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Announce sites already nearby",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (announceAtStart) {
                            "Testing: everything in range speaks when the trip starts"
                        } else {
                            "Normally off — you don't need telling about where you're parked"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = announceAtStart,
                    onCheckedChange = { TripPreferences.setAnnounceAtStart(context, it) },
                )
            }
        }

        if (denied) {
            Text(
                "Location permission is required to notice what you are driving past.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (mapFailed) {
            Text(
                "No map app is installed to show this location.",
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
                    val isSpeaking = speakingId == alert.marker.geomId
                    AlertCard(
                        alert = alert,
                        isSpeaking = isSpeaking,
                        onSpeak = {
                            if (isSpeaking) Speech.clear()
                            else Speech.speak(context, alert.marker)
                        },
                        onMap = { mapFailed = !openMap(context, alert.marker) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: TripAlert,
    isSpeaking: Boolean,
    onSpeak: () -> Unit,
    onMap: () -> Unit,
) {
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
                TextButton(onClick = onMap) { Text("Map") }
                TextButton(onClick = onSpeak) { Text(if (isSpeaking) "Stop" else "Play") }
            }
        }
    }
}
