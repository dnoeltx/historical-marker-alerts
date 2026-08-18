package com.dnoel.markeralerts.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = ForestGreen,
    secondary = Sandstone,
    tertiary = Slate,
)

private val DarkColors = darkColorScheme(
    primary = ForestGreenLight,
    secondary = SandstoneLight,
    tertiary = SlateLight,
)

@Composable
fun MarkerAlertsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color pulls the scheme from the user's wallpaper on Android 12+.
    // Exposed as a parameter so previews and screenshot tests can pin it off
    // and stay deterministic.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
