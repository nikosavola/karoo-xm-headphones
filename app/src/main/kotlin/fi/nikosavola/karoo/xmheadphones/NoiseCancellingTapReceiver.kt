package fi.nikosavola.karoo.xmheadphones

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tap on the noise cancelling ride field asks for the next mode.
 *
 * Karoo inflates the field in its own process, so the only channel back is a broadcast. The
 * PendingIntent is created here, which lets this receiver stay unexported.
 *
 * The field can outlive the extension's process, so the tap takes its own hold on the client. The
 * hold is reference counted, so it does not disturb one the extension still has.
 */
internal class NoiseCancellingTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The client's answer arrives later, so the broadcast is held open until the headset has
        // taken the change or given up.
        val pending = goAsync()
        val client = XmHeadphonesClient.get(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            client.start()
            try {
                withTimeoutOrNull(TAP_TIMEOUT_MS) { client.cycleNoiseCancelling(TAP_TIMEOUT_MS) }
            } finally {
                client.stop()
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        /** Under the ten seconds a broadcast is given before the system calls it an ANR. */
        const val TAP_TIMEOUT_MS = 8_000L
    }
}
