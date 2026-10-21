package fi.nikosavola.karoo.xmheadphones

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource

/**
 * The palette Sound Connect uses: a near black screen with an amber accent, and the same accent on a
 * warm light ground for daylight.
 *
 * The point of taking it from there is recognition. A rider who has set their headset up in Sony's
 * own app already knows the combination, and the selected mode and preset then read the same way on
 * the bike computer as they do on the phone.
 *
 * Which of the two is drawn follows the platform's night mode, which Karoo sets itself by time of
 * day. A screen that stayed black at noon is a screen a rider cannot read in the sun.
 *
 * Applied at the activity and in the tests, not by the screen itself, which is a function of the
 * state it is given and knows nothing about how it is coloured.
 */
@Composable
internal fun XmHeadphonesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = sonyColors(isSystemInDarkTheme()), content = content)
}

/**
 * The ground colour comes from resources rather than a literal here, because the window and the
 * Android 12 splash are told to use the same resource. Two copies of it drift, and the drift is a
 * flash of a different colour on every launch.
 */
@Composable
private fun sonyColors(dark: Boolean): ColorScheme {
    val background = colorResource(R.color.app_background)
    return remember(dark, background) {
        if (dark) {
            darkColorScheme(
                primary = Color(0xFFFFC61A),
                onPrimary = Color(0xFF241C00),
                primaryContainer = Color(0xFF4A3B00),
                onPrimaryContainer = Color(0xFFFFE7A3),
                secondary = Color(0xFFE9A13B),
                onSecondary = Color(0xFF2A1800),
                secondaryContainer = Color(0xFF5C3D00),
                onSecondaryContainer = Color(0xFFFFDCB0),
                background = background,
                onBackground = Color(0xFFEDE7DC),
                surface = background,
                onSurface = Color(0xFFEDE7DC),
                surfaceVariant = Color(0xFF2A2620),
                onSurfaceVariant = Color(0xFFBDB6A8),
                outline = Color(0xFF6E6659),
                // Red rather than amber, so a headset about to run out does not read as a normal one.
                error = Color(0xFFFF5A36),
                onError = Color(0xFF3A0F00),
            )
        } else {
            // The amber is darkened until it passes as text on the light ground; the accent itself
            // carries the same hue, so the two screens are recognisably the same app.
            lightColorScheme(
                primary = Color(0xFF7A5200),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFFFDF9E),
                onPrimaryContainer = Color(0xFF261A00),
                secondary = Color(0xFF6F5B00),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFFFE16B),
                onSecondaryContainer = Color(0xFF221B00),
                background = background,
                onBackground = Color(0xFF1D1B16),
                surface = background,
                onSurface = Color(0xFF1D1B16),
                surfaceVariant = Color(0xFFEAE3D6),
                onSurfaceVariant = Color(0xFF4E4639),
                outline = Color(0xFF807669),
                error = Color(0xFFB3261E),
                onError = Color(0xFFFFFFFF),
            )
        }
    }
}
