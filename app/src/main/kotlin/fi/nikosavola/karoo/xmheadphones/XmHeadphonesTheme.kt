package fi.nikosavola.karoo.xmheadphones

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource

/**
 * The palette Sound Connect uses: a near black screen with an amber accent.
 *
 * The point of taking it from there is recognition. A rider who has set their headset up in Sony's
 * own app already knows this combination, and the selected mode and preset then read the same way
 * on the bike computer as they do on the phone.
 *
 * Applied at the activity and in the tests, not by the screen itself, which is a function of the
 * state it is given and knows nothing about how it is coloured.
 */
@Composable
internal fun XmHeadphonesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = sonyColors(), content = content)
}

/**
 * The ground colour comes from resources rather than a literal here, because the window and the
 * Android 12 splash are told to use the same resource. Two copies of it drift, and the drift is a
 * flash of a different colour on every launch.
 */
@Composable
private fun sonyColors(): ColorScheme {
    val background = colorResource(R.color.app_background)
    return remember(background) {
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
            // Red rather than amber, so a headset about to run out does not read as a normal state.
            error = Color(0xFFFF5A36),
            onError = Color(0xFF3A0F00),
        )
    }
}
