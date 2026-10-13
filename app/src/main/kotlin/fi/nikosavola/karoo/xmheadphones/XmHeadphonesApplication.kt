package fi.nikosavola.karoo.xmheadphones

import android.app.Application
import timber.log.Timber

/**
 * Plants a log tree for debug builds.
 *
 * The extension and the client log through Timber, which is silent until planted. Release
 * builds stay quiet. A rider's log is no place for a headset's name.
 */
class XmHeadphonesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
