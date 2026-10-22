package fi.nikosavola.karoo.xmheadphones

import android.app.Application
import android.os.StrictMode
import timber.log.Timber

/**
 * Plants a log tree for debug builds, and turns on StrictMode there.
 *
 * The extension and the client log through Timber, which is silent until planted. Release
 * builds stay quiet. A rider's log is no place for a headset's name.
 *
 * StrictMode only reports. A debug build that dies on a disk read would make the tests and the
 * screenshot task unusable, and a logged violation is enough to find the call.
 */
class XmHeadphonesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build()
            )
            Timber.plant(Timber.DebugTree())
        }
    }
}
