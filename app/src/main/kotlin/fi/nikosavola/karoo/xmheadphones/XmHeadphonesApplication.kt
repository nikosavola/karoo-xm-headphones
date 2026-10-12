package fi.nikosavola.karoo.xmheadphones

import android.app.Application
import timber.log.Timber

/**
 * Plants a log tree for debug builds.
 *
 * Both the extension and the client report what a headset does through Timber, and Timber says
 * nothing until something is planted, so without this the diagnostics for a headset that misbehaves
 * would not reach logcat at all. Release builds stay quiet: there is nowhere for them to go, and a
 * rider's log is not the place to send a headset's name.
 */
class XmHeadphonesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
