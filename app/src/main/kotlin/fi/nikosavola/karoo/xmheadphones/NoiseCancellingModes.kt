package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Where a mode change starts when no one has named a level. */
private val DEFAULT_AMBIENT_LEVEL: AmbientLevel = AmbientLevel.clamped(10)

/** How long a write waits for the headset to report the mode it was told to take. */
private const val REPORT_TIMEOUT_MS = 1_000L

/**
 * One cycle at a time, so two quick taps take two steps rather than both starting from the mode
 * the headset reported before either write.
 */
private val cycleLock = Mutex()

/** The level to come back to. Ambient's own when on, else the rider's last. */
internal fun XmHeadphonesState.ambientLevel(): AmbientLevel =
    (noiseCancelling as? NoiseCancelling.Ambient)?.level ?: lastAmbientLevel ?: DEFAULT_AMBIENT_LEVEL

internal fun XmHeadphonesState.focusOnVoice(): Boolean =
    (noiseCancelling as? NoiseCancelling.Ambient)?.focusOnVoice ?: lastFocusOnVoice ?: false

/**
 * The mode the field moves to on a tap: off, cancelling, ambient, then off again.
 *
 * Null until the headset has reported a mode, because there is nothing to move on from.
 */
internal val XmHeadphonesState.nextNoiseCancelling: NoiseCancelling?
    get() =
        when (noiseCancelling) {
            null -> null
            NoiseCancelling.Off -> NoiseCancelling.Cancelling
            NoiseCancelling.Cancelling -> NoiseCancelling.Ambient(ambientLevel(), focusOnVoice())
            is NoiseCancelling.Ambient -> NoiseCancelling.Off
        }

/**
 * Moves a connected headset to its next mode, and answers whether the headset took it.
 *
 * Shared by the controller action and a tap on the ride field. Both can arrive before the link is
 * up, so the wait is bounded rather than left to the caller.
 */
internal suspend fun XmHeadphonesClient.cycleNoiseCancelling(timeoutMillis: Long): Boolean =
    cycleLock.withLock {
        wake()
        val link = withTimeoutOrNull(timeoutMillis) { state.first { it.link.isSettled() } }?.link
        if (link != HeadphonesLink.Connected) {
            Timber.w("Nothing to change; the headset link is $link")
            return@withLock false
        }
        val next = state.value.nextNoiseCancelling ?: return@withLock false
        if (!setNoiseCancelling(next)) return@withLock false
        // The acknowledgement comes back before the headset's report does. Without waiting for the
        // report, a second tap queued on the lock would still see the mode before this write.
        withTimeoutOrNull(REPORT_TIMEOUT_MS) {
            state.first { it.noiseCancelling?.sameModeAs(next) == true }
        }
        return@withLock true
    }

/** Whether two settings are the same mode. An ambient level may come back slightly different. */
private fun NoiseCancelling.sameModeAs(other: NoiseCancelling): Boolean =
    when (this) {
        NoiseCancelling.Off -> other is NoiseCancelling.Off
        NoiseCancelling.Cancelling -> other is NoiseCancelling.Cancelling
        is NoiseCancelling.Ambient -> other is NoiseCancelling.Ambient
    }
