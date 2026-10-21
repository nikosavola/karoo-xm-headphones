package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Whether the ride field's RemoteViews carries the tap that moves the mode on.
 *
 * Without this, deleting the click from the field leaves every other test green: the extension
 * tests send the receiver's intent directly and never build the view.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class NoiseCancellingFieldTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a ride field takes a tap and the preview does not`() = runBlocking {
        assertTrue("the field is not clickable on a ride", clickable(field(preview = false)))
        // A clickable preview would take the tap that replaces the field in the profiles app.
        assertFalse("the field is clickable in the preview", clickable(field(preview = true)))
    }

    private suspend fun field(preview: Boolean): View =
        noiseCancellingFieldViews(
                context,
                ViewConfig(gridSize = 30 to 15, viewSize = 200 to 100, textSize = 14, preview = preview),
                "Noise cancelling",
            )
            .apply(context, FrameLayout(context))

    /** The click can be on the root or on a child, depending on how Glance nests the box. */
    private fun clickable(view: View): Boolean =
        view.hasOnClickListeners() ||
            (view as? ViewGroup)?.children?.any(::clickable) == true
}
