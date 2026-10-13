package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * The launcher icon, which is the one image no screenshot covers.
 *
 * Karoo draws it, not this app, so a screen test cannot see it at all: not a glyph that a launcher's
 * mask would cut into, and not a colour a launcher resolves against its own theme rather than this
 * app's. Both are drawn here and read back from the pixels. Run at both ends of the supported range,
 * since the monochrome layer an adaptive icon may carry is a tag only the newest of them knows.
 *
 * An adaptive icon is a 108dp canvas of which the launcher guarantees the middle 72dp survives
 * whatever mask it cuts the icon with. The mask is a shape rather than a square: a circle is one of
 * them, so what the icon has to say has to fit inside that square's inscribed circle, not merely
 * inside the square.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 33])
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the launcher icon is an adaptive one, so a launcher can mask it`() {
        val icon = checkNotNull(context.getDrawable(R.mipmap.ic_launcher))

        // A plain drawable is a legacy icon, which a launcher shrinks onto a background of its own
        // choosing, and an adaptive one is drawn to fit whatever it is given.
        assertTrue(
            "the launcher icon is a ${icon::class.java.simpleName}",
            icon is AdaptiveIconDrawable,
        )
    }

    @Test
    fun `the icon is drawn on the app's own colour rather than a theme's`() {
        val bitmap = draw(composite(), CANVAS)

        // Above the glyph and on its centre line, so nothing else has been drawn over it. The point
        // of reading it here is that nothing in the launcher's theme can change it.
        assertEquals(
            context.getColor(R.color.launcher_background),
            bitmap.getPixel(CANVAS / 2, CANVAS * 8 / 108),
        )
    }

    @Test
    fun `the glyph stays inside the circle a launcher's mask keeps`() {
        val ink = inkOf(draw(foreground(), CANVAS))

        assertTrue("the icon draws no glyph at all", ink.count > 0)
        // A mask cuts from the middle outwards, so a glyph filling the corners of the safe square
        // is a glyph whose corners no rider ever sees.
        assertTrue(
            "the glyph reaches ${ink.furthest}px from the centre, past the $RADIUS a round mask keeps",
            ink.furthest <= RADIUS,
        )
        val middle = (CANVAS / 2).toDouble()
        assertEquals("the glyph is not centred across", middle, ink.middleX, CENTRED_PX.toDouble())
        assertEquals("the glyph is not centred down", middle, ink.middleY, CENTRED_PX.toDouble())
    }

    /** The whole icon, or just the layer the glyph is drawn in. */
    private fun composite(): Drawable = checkNotNull(context.getDrawable(R.mipmap.ic_launcher))

    private fun foreground(): Drawable = checkNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))

    private fun draw(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Where the glyph is drawn, in canvas pixels.
     *
     * By coverage rather than by colour: the icon is drawn out of a vector, so its edges are a blend
     * of the two colours and belong to neither.
     */
    private fun inkOf(bitmap: Bitmap): Ink {
        val ink = Ink()
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) ushr 24 == 0) continue
                ink.cover(x, y)
            }
        }
        return ink
    }

    private class Ink {
        var count = 0
        private var left = Int.MAX_VALUE
        private var right = Int.MIN_VALUE
        private var top = Int.MAX_VALUE
        private var bottom = Int.MIN_VALUE
        var furthest = 0.0
            private set

        fun cover(x: Int, y: Int) {
            count++
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
            furthest = maxOf(furthest, hypot((x - CENTRE).toDouble(), (y - CENTRE).toDouble()))
        }

        val middleX: Double get() = (left + right) / 2.0
        val middleY: Double get() = (top + bottom) / 2.0

        private companion object {
            const val CENTRE = CANVAS / 2
        }
    }

    private companion object {
        /** The 108dp adaptive icon canvas, at 4x, so a pixel is a quarter of a dp. */
        const val CANVAS = 432

        /** The 36dp radius of the circle inside the safe square, which is the least a mask keeps. */
        const val RADIUS = 36 * CANVAS / 108

        /** A dp either way, which is inside what a mask wastes anyway. */
        const val CENTRED_PX = 4
    }
}
