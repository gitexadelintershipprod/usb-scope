package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.uvc.UvcUnsupportedException

/**
 * Picks which attached device a session loop should try next.
 *
 * Every poll used to take the *first* device some driver claimed and nothing else, which was fine
 * while only the vendor drivers existed: they match one USB id. [UvcBulkDriver][com.technicallyvu.scope.core.uvc.UvcBulkDriver]
 * matches any UVC function, so the first match can now be a laptop's built-in webcam, or any camera
 * whose `open` fails — and that device would shadow the real endoscope for as long as it stays
 * plugged in. Two rules stop it:
 *
 *  - **Skip what can never work.** A [UvcUnsupportedException] is permanent by definition (an
 *    isochronous-only endpoint, no MJPEG/YUY2 format, not a UVC device); its [DeviceRef] is
 *    remembered while it remains attached and never offered again during that attachment.
 *    Its rejection reason remains available so the UI can distinguish it from an absent camera.
 *  - **Rotate past what failed.** Any other open failure only moves the cursor: the next poll starts
 *    at the candidate *after* the one that failed, so a device with a transient problem costs one
 *    poll instead of blocking every other device forever. A device that did open is preferred on the
 *    following poll, so a reconnect goes straight back to the camera that was working.
 *
 * Not thread-safe: one instance per session loop, touched only from that loop.
 */
class DeviceCandidates(private val drivers: List<DeviceDriver>) {
    private val skip = mutableMapOf<DeviceRef, String>()
    private var lastFailed: DeviceRef? = null
    private var lastOpened: DeviceRef? = null

    /** Every (ref, driver) pair among [refs], in enumeration order, minus the permanently skipped. */
    fun candidates(refs: List<DeviceRef>): List<Pair<DeviceRef, DeviceDriver>> {
        // A physical detach clears both the rejection and its cached decision. If the same USB
        // address is subsequently reused, that new attachment gets its own opening attempt.
        skip.keys.retainAll(refs.toSet())
        return refs.mapNotNull { ref ->
            if (ref in skip) null else drivers.firstOrNull { it.matches(ref.info) }?.let { ref to it }
        }
    }

    /** A reason for an attached, permanently rejected device, after the latest successful poll. */
    fun rejectionReason(): String? = skip.values.firstOrNull()

    /** The candidate to try this poll, or null when there is none. */
    fun next(candidates: List<Pair<DeviceRef, DeviceDriver>>): Pair<DeviceRef, DeviceDriver>? {
        if (candidates.isEmpty()) return null
        lastFailed?.let { failed ->
            val i = candidates.indexOfFirst { it.first == failed }
            if (i >= 0) return candidates[(i + 1) % candidates.size]
        }
        lastOpened?.let { opened ->
            val i = candidates.indexOfFirst { it.first == opened }
            if (i >= 0) return candidates[i]
        }
        return candidates.first()
    }

    /** [ref] opened: it becomes the preferred candidate and the rotation cursor is cleared. */
    fun onOpened(ref: DeviceRef) {
        lastFailed = null
        lastOpened = ref
    }

    /** [ref] failed to open: rotate past it, and never offer it again if the failure was permanent. */
    fun onOpenFailed(ref: DeviceRef, e: UsbException) {
        if (e is UvcUnsupportedException) skip[ref] = e.message ?: "This USB camera is not supported."
        lastFailed = ref
    }
}
