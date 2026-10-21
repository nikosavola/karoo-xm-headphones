package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hammerhead.karooext.models.ViewConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the noise cancelling ride field in the three modes a tap moves through.
 *
 * The field is RemoteViews, inflated by Karoo rather than by this app, so this renders it the same
 * way and reads the text back before writing the README's image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w480dp-h160dp-night-xhdpi")
class NoiseCancellingFieldScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `draws the field in each mode a tap reaches`() {
        val labels =
            listOf(
                    R.string.nc_off,
                    R.string.nc_noise_cancelling,
                    R.string.nc_ambient,
                )
                .map(context::getString)

        val fields =
            runBlocking {
                labels.map {
                    noiseCancellingFieldViews(context, viewConfig(), it)
                        .apply(context, FrameLayout(context))
                }
            }

        fields.zip(labels).forEach { (field, label) ->
            assertEquals(listOf(label), textsOf(field))
        }

        val strip = strip(fields)
        val width = dp(STRIP_WIDTH_DP)
        val height = dp(STRIP_HEIGHT_DP)
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        strip.layout(0, 0, width, height)
        fields.forEach { field ->
            val label = labelsOf(field).single()
            assertEquals("a field label wrapped", 1, label.lineCount)
            assertTrue(
                "the label is ${label.width} wide in a ${field.width} field",
                label.width <= field.width,
            )
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        strip.draw(Canvas(bitmap))

        val directory = File(screenshotDirectory())
        assertTrue("could not create $directory", directory.isDirectory || directory.mkdirs())
        File(directory, "field-noise-cancelling.webp").outputStream().use { out ->
            @Suppress("InlinedApi")
            val written = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            assertTrue("could not write the field image", written)
        }
    }

    private fun strip(fields: List<View>): LinearLayout {
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(0x12, 0x12, 0x12))
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }
        column.addView(
            TextView(context).apply {
                text = "Tap the field to cycle through the modes."
                setTextColor(Color.rgb(0xA2, 0x9A, 0x8C))
                textSize = 13f
            },
        )
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        fields.forEach { field ->
            // Only for the image: Karoo draws no tile around a field, and without one the three
            // states read as loose words rather than as the same field three times.
            field.background =
                GradientDrawable().apply {
                    setColor(Color.rgb(0x1C, 0x1C, 0x1C))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.rgb(0x33, 0x33, 0x33))
                }
            row.addView(
                field,
                LinearLayout.LayoutParams(dp(FIELD_WIDTH_DP), dp(FIELD_HEIGHT_DP)).apply {
                    marginStart = dp(FIELD_GAP_DP)
                },
            )
        }
        column.addView(row)
        return column
    }

    private fun textsOf(view: View): List<String> =
        when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> view.children.flatMap(::textsOf).toList()
            else -> emptyList()
        }

    private fun labelsOf(view: View): List<TextView> =
        when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> view.children.flatMap(::labelsOf).toList()
            else -> emptyList()
        }

    private fun viewConfig() = ViewConfig(gridSize = 30 to 15, viewSize = 200 to 100, textSize = 14)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** The build default too, for a run that comes from somewhere other than Gradle. */
    private fun screenshotDirectory(): String =
        System.getProperty("xmheadphones.screenshotDir") ?: "build/screenshots"

    private companion object {
        const val FIELD_WIDTH_DP = 200
        const val FIELD_HEIGHT_DP = 100
        const val FIELD_GAP_DP = 8
        const val STRIP_WIDTH_DP = FIELD_WIDTH_DP * 3 + FIELD_GAP_DP * 4 + 32
        const val STRIP_HEIGHT_DP = 150
    }
}
