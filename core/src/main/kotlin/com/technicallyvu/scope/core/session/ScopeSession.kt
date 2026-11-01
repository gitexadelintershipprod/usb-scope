package com.technicallyvu.scope.core.session

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.image.Sharpener
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.usb.DeviceRef
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

sealed interface ConnectionState {
    data class NoDevice(val needsDriverHint: Boolean) : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Streaming(val name: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class SessionState(
    val connection: ConnectionState = ConnectionState.NoDevice(false),
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val stats: StreamStats = StreamStats(),
    val recording: Boolean = false,
    val lastSaved: String? = null,
    val denoise: Boolean = true,
    val denoiseStrength: Float = 0.6f,
    /**
     * Cosmetic luma unsharp mask, applied *after* [denoise]. Off by default: the camera's 320x240 is
     * all there is, and sharpening only makes edges read as crisper — it recovers no detail.
     */
    val sharpen: Boolean = false,
    val sharpenStrength: Float = Sharpener.DEFAULT_STRENGTH,
    /** The currently-streaming driver's id, or null when no session is [ConnectionState.Streaming]. */
    val driverId: String? = null,
    /** Retained until a valid frame arrives so automatic reconnect cannot hide a transfer error. */
    val lastUsbError: String? = null,
)

/**
 * Receives frames on the session's worker thread. Implementations must be fast and must not block.
 * If a call throws, the session drops that frame (or button event) and continues; it does not end
 * the stream. The sink owns its own error reporting.
 */
interface FrameSink {
    fun onFrame(frame: Frame, state: SessionState)
    /** The cable button was pressed (debounced rising edge). */
    fun onButtonSnapshot()
    /**
     * A second cable-button press landed within [ScopeSession.doublePressWindowNanos] of the
     * first (debounced rising edge); the first press already took its snapshot via
     * [onButtonSnapshot], this one toggles recording instead. Default no-op for sinks that don't
     * support recording.
     */
    fun onButtonRecordToggle() {}
    /**
     * A device just opened and the session has entered [ConnectionState.Streaming]; the next
     * [onFrame] belongs to a new stream. Sinks that keep per-stream history (a decoded-frame
     * denoiser, for instance) reset it here. Called once per successful open, on the session's
     * worker thread, before any frame of that stream.
     */
    fun onStreamStarted() {}
}

/**
 * Platform-free session loop shared by shells: polls [devices], opens a device some driver claims,
 * streams frames to [sink], and reconnects after errors. Which device is tried on a given poll is
 * [DeviceCandidates]' business — one that cannot work is skipped, one that failed is rotated past.
 * Owns rotation/mirror and the recording flag (which locks them); the shell owns snapshot/recording
 * implementations.
 */
class ScopeSession(
    private val devices: DeviceSource,
    private val drivers: List<DeviceDriver>,
    private val scope: CoroutineScope,
    private val sink: FrameSink,
    private val driverHintCheck: () -> Boolean = { false },
    private val pollMillis: Long = 1000,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    @Volatile private var job: Job? = null
    @Volatile private var denoiser: TemporalDenoiser? = TemporalDenoiser()
    /** Null while sharpening is off; see [setSharpen]. Stateless apart from a scratch buffer. */
    @Volatile private var sharpener: Sharpener? = null
    // Written from the session coroutine on every open, and cleared from any thread by
    // clearDefaultOverride (a settings screen), hence volatile.
    @Volatile private var lastDriverId: String? = null
    // The following are only ever read/written from the session coroutine (started in `start()`,
    // confined to `workDispatcher`), so they need no synchronization of their own.
    private var prevButton = false
    private var lastButtonNanos: Long? = null
    /**
     * Timestamp of an unconsumed single press: set when a press takes a snapshot, cleared once a
     * second press within [DOUBLE_PRESS_WINDOW_NANOS] consumes it as a toggle (or once a press
     * outside the window replaces it with a new pending press of its own).
     */
    private var lastPressNanos: Long? = null
    private val candidates = DeviceCandidates(drivers)
    private var rejectedDeviceMessage: String? = null

    /**
     * A second press within this long of the first toggles recording instead of snapshotting
     * again. Defaults to [DOUBLE_PRESS_WINDOW_NANOS]; clamped to 500 ms..5 s so a bad settings value
     * can't make double-press detection unusable. Read from the session's worker thread, written
     * from any thread (a settings screen), hence volatile.
     *
     * The initializer below assigns the backing field directly (Kotlin does not run a custom setter
     * for it), so the clamp never sees the default -- [DOUBLE_PRESS_WINDOW_NANOS] is inside the
     * range by construction, and the constants test keeps it that way.
     */
    @Volatile var doublePressWindowNanos: Long = DOUBLE_PRESS_WINDOW_NANOS
        set(value) { field = value.coerceIn(MIN_DOUBLE_PRESS_WINDOW_NANOS, MAX_DOUBLE_PRESS_WINDOW_NANOS) }

    /** Per-driver rotation/mirror overrides, consulted when a session starts for that driver. */
    private data class DriverDefaultOverride(val rotation: Int?, val mirror: Boolean?)
    private val defaultOverrides = ConcurrentHashMap<String, DriverDefaultOverride>()

    fun start() {
        if (job != null) return
        job = scope.launch(workDispatcher) {
            while (isActive) {
                try {
                    val found = candidates.next(findCandidates())
                    if (found == null) {
                        val rejection = rejectedDeviceMessage
                        _state.update {
                            it.copy(connection = if (rejection != null) ConnectionState.Failed(rejection)
                                else ConnectionState.NoDevice(driverHintCheck()))
                        }
                    } else {
                        session(found.first, found.second)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)) }
                }
                delay(pollMillis)
            }
        }
    }

    /**
     * Cancels the loop and suspends up to [timeoutMillis] waiting for the transport to close.
     * Returns true if the join completed within the timeout, false if it timed out. Preferred over
     * [stop] on Android, where nothing may block the main thread.
     *
     * On a timeout, [job] is deliberately left set rather than nulled, so a subsequent [start]
     * call is a no-op instead of racing a second loop against the one still winding down.
     */
    suspend fun stopAndJoin(timeoutMillis: Long = 3_000): Boolean {
        val j = job ?: return true
        j.cancel()
        val joined = withTimeoutOrNull(timeoutMillis) { j.join() } != null
        if (joined) job = null
        return joined
    }

    /**
     * Cancels the loop and blocks the calling thread up to [timeoutMillis] waiting for the
     * transport to close. Returns true if the join completed within the timeout, false if it
     * timed out. Safe to call from any thread; on Android call it off the main thread or from a
     * lifecycle teardown that tolerates blocking — otherwise use [stopAndJoin].
     */
    fun stop(timeoutMillis: Long = 3_000): Boolean = runBlocking { stopAndJoin(timeoutMillis) }

    fun rotate() = _state.update { if (it.recording) it else it.copy(rotation = (it.rotation + 90) % 360) }
    fun toggleMirror() = _state.update { if (it.recording) it else it.copy(mirror = !it.mirror) }
    fun setRecording(active: Boolean) = _state.update { it.copy(recording = active) }
    fun markSaved(name: String) = _state.update { it.copy(lastSaved = name) }

    /**
     * Turns the temporal denoiser on/off and/or updates its blend [strength] (clamped to
     * [MIN_DENOISE_STRENGTH]..[MAX_DENOISE_STRENGTH]). When enabling, an existing denoiser has its
     * strength updated live; there is none yet (or it was off), a fresh one is created with that
     * strength. The clamped strength is always recorded in state, even while denoise is off, so a
     * later `setDenoise(true)` (default parameter) picks it back up.
     */
    fun setDenoise(enabled: Boolean, strength: Float = state.value.denoiseStrength) {
        val clamped = strength.coerceIn(MIN_DENOISE_STRENGTH, MAX_DENOISE_STRENGTH)
        denoiser = if (enabled) (denoiser?.also { it.strength = clamped } ?: TemporalDenoiser(clamped)) else null
        _state.update { it.copy(denoise = enabled, denoiseStrength = clamped) }
    }

    /**
     * Turns the cosmetic sharpener on/off and/or updates its [strength] (clamped to
     * [MIN_SHARPEN_STRENGTH]..[MAX_SHARPEN_STRENGTH]). Mirrors [setDenoise]: an existing sharpener
     * has its strength updated live, and the clamped strength is recorded in state even while
     * sharpening is off, so a later `setSharpen(true)` picks it back up.
     */
    fun setSharpen(enabled: Boolean, strength: Float = state.value.sharpenStrength) {
        val clamped = strength.coerceIn(MIN_SHARPEN_STRENGTH, MAX_SHARPEN_STRENGTH)
        sharpener = if (enabled) (sharpener?.also { it.strength = clamped } ?: Sharpener(clamped)) else null
        _state.update { it.copy(sharpen = enabled, sharpenStrength = clamped) }
    }

    /**
     * Sets the rotation/mirror this session applies whenever it starts streaming for [driverId]
     * (either flag null keeps that flag alone: [DeviceDriver.defaultRotation] / `false` on a driver
     * change, the value already in state on a reconnect of the same driver). An override is sticky:
     * unlike the plain driver default, it is reapplied on *every* open of that driver, including an
     * unplug/replug of the same probe.
     *
     * If [driverId] is the driver streaming right now, the non-null flags also take effect
     * immediately, so a "this device" settings screen shows its change on the live preview. The
     * recording lock is honoured, as it is by [rotate]/[toggleMirror]: geometry may not change
     * mid-recording, but the override is still stored and applies at the next open.
     */
    fun setDefaultOverride(driverId: String, rotation: Int?, mirror: Boolean?) {
        defaultOverrides[driverId] = DriverDefaultOverride(rotation, mirror)
        _state.update {
            if (it.driverId != driverId || it.recording) it
            else it.copy(rotation = rotation ?: it.rotation, mirror = mirror ?: it.mirror)
        }
    }

    /**
     * Forgets [driverId]'s override, so its next open falls back to [DeviceDriver.defaultRotation].
     * Deliberately leaves the live session alone: nothing about the current view is "wrong" just
     * because the stored default was dropped.
     *
     * Dropping the map entry is not enough on its own: if [driverId] is the driver this session
     * last opened, the next open would take the "same driver, no override" branch and keep whatever
     * rotation state happens to hold, so "Forget" would visibly forget nothing. Clearing
     * [lastDriverId] too makes that open look like a driver change, which is the fallback the KDoc
     * above promises.
     */
    fun clearDefaultOverride(driverId: String) {
        defaultOverrides.remove(driverId)
        if (lastDriverId == driverId) lastDriverId = null
    }

    /** Every attached device a driver claims, in enumeration order. A list error means "none, this poll". */
    private fun findCandidates(): List<Pair<DeviceRef, DeviceDriver>> = try {
        candidates.candidates(devices.list()).also { rejectedDeviceMessage = candidates.rejectionReason() }
    } catch (e: UsbException) {
        rejectedDeviceMessage = null
        emptyList()
    }

    private suspend fun session(ref: DeviceRef, driver: DeviceDriver) {
        _state.update { it.copy(connection = ConnectionState.Connecting(driver.displayName)) }
        var transport: UsbTransport? = null
        var source: FrameSource? = null
        var opened = false
        try {
            transport = devices.open(ref)
            source = driver.open(transport)
            opened = true
            candidates.onOpened(ref)
            // Only reset rotation/mirror to the driver default (or its override) once the device has
            // actually opened, so a device that keeps failing to open does not wipe the user's
            // rotation every poll. A *driver default* applies on a driver change only, so a same-
            // driver reconnect keeps whatever the user last set; a user-set override applies on
            // every open of its driver, so a "this device" default is not lost to an unplug/replug.
            val override = defaultOverrides[driver.id]
            val driverChanged = driver.id != lastDriverId
            if (driverChanged || override != null) {
                _state.update {
                    it.copy(
                        rotation = override?.rotation ?: if (driverChanged) driver.defaultRotation else it.rotation,
                        mirror = override?.mirror ?: if (driverChanged) false else it.mirror,
                    )
                }
            }
            lastDriverId = driver.id
            denoiser?.reset()
            _state.update { it.copy(connection = ConnectionState.Streaming(driver.displayName), driverId = driver.id) }
            // Same contract as onFrame: a throwing sink must not end the stream.
            try {
                sink.onStreamStarted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the sink owns its own error reporting
            }
            val src = source
            src.frames.collect { frame -> onFrame(frame, src.stats.value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsbException) {
            // Only a failure to *open* rotates the cursor; a stream that ended keeps this device first.
            if (!opened) candidates.onOpenFailed(ref, e)
            val wasStreaming = _state.value.connection is ConnectionState.Streaming
            _state.update {
                it.copy(connection = if (wasStreaming) ConnectionState.NoDevice(false) else if (driverHintCheck()) ConnectionState.NoDevice(true) else ConnectionState.Failed(e.message ?: "USB error"), lastUsbError = e.message ?: "USB error")
            }
        } catch (e: Exception) {
            _state.update { it.copy(connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)) }
        } finally {
            _state.update { it.copy(recording = false, driverId = null) }
            source?.let { runCatching { it.close() } }
            if (source == null) transport?.let { runCatching { it.close() } }
            prevButton = false
            lastButtonNanos = null
            lastPressNanos = null
        }
    }

    /**
     * The image filters, in the only order that makes sense: **denoise first, then sharpen**. An
     * unsharp mask amplifies exactly the high-frequency content sensor grain lives in, so sharpening
     * a raw frame and denoising afterwards would spend the denoiser's effort undoing the sharpener's.
     *
     * Returns null when there is nothing to do (a non-YUYV frame, or both filters off), in which
     * case the caller delivers the driver's own frame untouched.
     */
    private fun filter(yuv: FrameData.Yuyv422?): FrameData.Yuyv422? {
        if (yuv == null) return null
        val denoised = denoiser?.apply(yuv)
        val s = sharpener ?: return denoised
        // Sharpen into a frame of our own, never in place. Neither input is ours to write: the
        // driver owns its read buffer, and the denoiser keeps the array it just returned as its
        // previous-frame state, so sharpening it would feed every frame's overshoot into the next
        // blend (see TemporalDenoiser.apply). One fresh array per frame, written exactly once.
        return s.sharpened(denoised ?: yuv)
    }

    private fun onFrame(frame: Frame, stats: StreamStats) {
        _state.update { it.copy(stats = stats, lastUsbError = null) }
        val delivered = filter(frame.data as? FrameData.Yuyv422)?.let { filtered ->
            Frame(filtered, frame.timestampNanos, frame.buttonPressed, frame.cameraNumber, frame.sensorValue)
        } ?: frame
        // Only Exception is swallowed (never Error, never cancellation): the shell owns its own error reporting.
        try {
            sink.onFrame(delivered, _state.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // drop the frame and keep streaming
        }
        val last = lastButtonNanos
        if (frame.buttonPressed && !prevButton && (last == null || frame.timestampNanos - last > BUTTON_DEBOUNCE_NANOS)) {
            lastButtonNanos = frame.timestampNanos
            val prevPress = lastPressNanos
            if (prevPress != null && frame.timestampNanos - prevPress <= doublePressWindowNanos) {
                // Second press of a pair: the first already took its snapshot, this one toggles.
                lastPressNanos = null
                try {
                    sink.onButtonRecordToggle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // drop the button event and keep streaming
                }
            } else {
                lastPressNanos = frame.timestampNanos
                try {
                    sink.onButtonSnapshot()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // drop the button event and keep streaming
                }
            }
        }
        prevButton = frame.buttonPressed
    }

    companion object {
        const val BUTTON_DEBOUNCE_NANOS = 300_000_000L
        /** A second press within this long of the first toggles recording instead of snapshotting again. */
        const val DOUBLE_PRESS_WINDOW_NANOS = 1_500_000_000L
        const val MIN_DOUBLE_PRESS_WINDOW_NANOS = 500_000_000L
        const val MAX_DOUBLE_PRESS_WINDOW_NANOS = 5_000_000_000L
        const val MIN_DENOISE_STRENGTH = 0.2f
        const val MAX_DENOISE_STRENGTH = 1.0f
        const val MIN_SHARPEN_STRENGTH = Sharpener.MIN_STRENGTH
        const val MAX_SHARPEN_STRENGTH = Sharpener.MAX_STRENGTH
    }
}
