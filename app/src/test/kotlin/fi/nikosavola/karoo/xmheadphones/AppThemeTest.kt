package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the activity's theme does before Compose has drawn anything.
 *
 * Neither of these is in a screenshot of the screen. One is the chrome that would sit above it, and
 * the other is the colour on show for the moment before the screen draws, which from Android 12 is
 * also what the system splash puts behind the icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val theme = ContextThemeWrapper(context, R.style.Theme_XmHeadphones).theme

    @Test
    fun `the app is pointed at this theme, so any of it reaches the screen at all`() {
        // The style above is only worth anything if the manifest names it. Nothing else couples the
        // two, and a theme that nothing applies is a title bar nobody notices until a rider does.
        val application =
            context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(R.style.Theme_XmHeadphones, application.theme)
    }

    @Test
    fun `the theme puts no title bar above the screen's own headline`() {
        val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowNoTitle))
        val noTitle = attributes.getBoolean(0, false)
        attributes.recycle()

        // Left on, the framework draws the activity label in a bar above a screen that already says
        // the same name in its own headline.
        assertTrue("the framework would draw the activity label above the screen", noTitle)
    }

    @Test
    fun `the window is the colour the screen draws over`() {
        val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        val window = attributes.getDrawable(0)
        attributes.recycle()

        assertTrue(
            "the window background is a ${window?.javaClass?.simpleName} rather than a colour",
            window is ColorDrawable,
        )
        assertEquals(
            "the launch and the splash would flash a colour the screen does not use",
            context.getColor(R.color.app_background),
            (window as ColorDrawable).color,
        )
    }
}
