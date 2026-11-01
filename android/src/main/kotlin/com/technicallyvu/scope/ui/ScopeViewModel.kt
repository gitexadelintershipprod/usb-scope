package com.technicallyvu.scope.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.driver.DriverRegistry
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.fixture.ReplayDeviceSource
import com.technicallyvu.scope.core.i4season.I4seasonYuvDriver
import com.technicallyvu.scope.core.image.ExifOrientation
import com.technicallyvu.scope.core.image.Sharpener
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.core.session.FrameSink
import com.technicallyvu.scope.core.session.ScopeSession
import com.technicallyvu.scope.core.session.SessionState
import com.technicallyvu.scope.core.usb.DeviceSource
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.media.FrameBitmaps
import com.technicallyvu.scope.media.MediaStoreSaver
import com.technicallyvu.scope.media.SurfaceRecorder
import com.technicallyvu.scope.settings.AppSettings
import com.technicallyvu.scope.settings.DriverDefault
import com.technicallyvu.scope.settings.SessionSettingsTarget
import com.technicallyvu.scope.settings.Settings
import com.technicallyvu.scope.settings.SettingsApplier
import com.technicallyvu.scope.usb.UsbEventTrace
import com.technicallyvu.scope.usb.AndroidDeviceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Which full-screen destination is showing. */
enum class Screen { Live, Settings, Trust }

/**
 * Where a cable-button event (a snapshot, or a double-press record toggle) leaves the user.
 *
 * Always the live view, whatever was showing. The button keeps working behind Settings and About —
 * the session never stops — and every sign that a press happened lives on [Screen.Live]: the REC
 * badge, the shutter flash, the "Saved" toast and the snackbar host. Without this rule a
 * double-press from Settings starts writing a clip with nothing on screen saying so, which is the
 * exact hazard the `enabled = !recording` guards on the Settings and About buttons exist to
 * prevent.
 *
 * A pure function of the current destination rather than an `if` buried in the view model, so the
 * rule itself is pinned by a plain JVM test.
 */
@Suppress("UNUSED_PARAMETER")
fun screenAfterButtonEvent(current: Screen): Screen = Screen.Live

data class UiState(
    val session: SessionState = SessionState(),
    val image: Bitmap? = null,
    val showStats: Boolean = false,
    val screen: Screen = Screen.Live,
    /**
     * Where [Screen.Trust]'s back arrow (and back gesture) returns to. [Screen] is a flat enum with
     * no back stack, and one screen pair needs one: About opened from Settings must go back to
     * Settings, not drop the user onto the live view. Set by [ScopeViewModel.navigate].
     */
    val returnTo: Screen = Screen.Live,
    val permissionDevice: UsbDevice? = null,
    val message: String? = null,
    /** Bumped with every [message], so the screen can tell two identical consecutive messages
     * apart; keying a `LaunchedEffect` on the text alone silently swallows the second one. */
    val messageTick: Long = 0L,
    val replaying: Boolean = false,
    /** Bumped on every cable-button event (snapshot or double-press toggle) so the screen can fire
     * a haptic tick via a `LaunchedEffect(ui.hapticTick)`; the initial value must not itself buzz. */
    val hapticTick: Long = 0L,
    /** A recording start is in flight (MediaStore + encoder setup); the Record button shows a
     * spinner and refuses further taps until it clears, either way. */
    val recordingStarting: Boolean = false,
    /** [System.nanoTime] of the moment the recorder was published, or null when not recording. */
    val recordingStartedAtNanos: Long? = null,
    /** A small thumbnail of the last saved photo, held for three seconds so the screen can confirm
     * the save, then cleared. */
    val lastSnapshotThumb: Bitmap? = null,
    /** Bumped the instant a snapshot is taken so the screen can blink a shutter flash; as with
     * [hapticTick], the initial value must not itself flash. */
    val flashTick: Long = 0L,
)

class ScopeViewModel(app: Application, private val appSettings: AppSettings) : AndroidViewModel(app) {
    private val usbManager = app.getSystemService(UsbManager::class.java)
    private val usbDevices = AndroidDeviceSource(usbManager) {
        ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    private val saver = MediaStoreSaver(app)

    // The stats overlay is a persisted preference; the initial UI state has to agree with the store
    // before the settings collector below has run even once.
    private val _ui = MutableStateFlow(UiState(showStats = appSettings.flow.value.showStats))
    val ui: StateFlow<UiState> = _ui

    /** The persisted preferences, for the settings screen to render and edit. */
    val settings: StateFlow<Settings> = appSettings.flow

    /**
     * Whether the activity should hold the screen awake: the preference AND an actual stream.
     * Nothing to watch means nothing to keep the screen on for, however the preference reads.
     */
    val keepScreenOn: StateFlow<Boolean> =
        combine(
            appSettings.flow,
            // Not _ui itself: it carries a new Bitmap on every frame, so combining it ran this
            // transform ~30×/s on Main for the view model's whole life. `stateIn` conflated the
            // result, so nothing downstream churned, but the work was still being done. One
            // boolean, de-duplicated, leaves the combine idle unless the stream actually starts or
            // stops.
            _ui.map { it.session.connection is ConnectionState.Streaming }.distinctUntilChanged(),
        ) { s, streaming -> s.keepScreenOn && streaming }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the per-second frame-timing log is being written; debug builds only, on by default. */
    private val _logFrameTiming = MutableStateFlow(true)

    /**
     * What the developer sheet renders. Derived from [_ui], which changes on every frame, but a
     * `StateFlow` drops equal values, so the sheet only recomposes when one of these two actually
     * flips. `WhileSubscribed` rather than `Eagerly`: nothing should be combining state per frame
     * for a sheet that is closed, and in a release build never opens at all.
     */
    val devState: StateFlow<DevState> =
        combine(_ui, _logFrameTiming) { u, t -> DevState(u.replaying, t) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(),
                DevState(replaying = _ui.value.replaying, logFrameTiming = _logFrameTiming.value),
            )

    @Volatile private lateinit var session: ScopeSession
    /** Pushes settings into the current session; replaced with the session in [attach]. */
    @Volatile private var applier: SettingsApplier? = null
    /** The last denoise flag pushed to a session, to spot an off→on transition. */
    private var lastAppliedDenoise: Boolean? = null
    private var stateJob: Job? = null
    @Volatile private var lastFrame: FrameData? = null
    @Volatile private var lastImage: Bitmap? = null
    private val recorderLock = Any()
    // Mutated only under recorderLock; @Volatile so the state collector can read "is anything
    // recording?" from the main thread without taking the lock.
    @Volatile private var recorder: SurfaceRecorder? = null
    @Volatile private var pendingVideo: MediaStoreSaver.PendingVideo? = null
    private var recorderGeneration = 0L
    /** Guards against a second replay start/stop while the previous session is still winding down. */
    private var switchingSession = false
    /** Identifies the current session's sink so a stale sink from a swapped-out session (see
     * [attach]) drops frames instead of racing the new one. Mutated only from [attach] on Main. */
    @Volatile private var currentSessionToken = 0L
    private var nextSessionToken = 0L
    /** The sink of the current session, so main-thread actions can reach its per-stream state. */
    @Volatile private var currentSink: SessionSink? = null
    /** Identifies the newest snapshot, so an older one's toast timer cannot clear its thumbnail. */
    private val snapshotGeneration = AtomicLong(0L)

    /** Publishes a formatted resource string as the transient snackbar message. */
    private fun message(@StringRes id: Int, vararg args: Any) {
        val text = getApplication<Application>().getString(id, *args)
        _ui.update { it.copy(message = text, messageTick = it.messageTick + 1) }
    }

    init {
        attach(usbDevices, replaying = false)
        // One collector for the life of the view model: every settings change (from the settings
        // screen, from a control that persists, or from a reset) is pushed into whichever session
        // is current. Sessions created later pick the settings up in [attach] instead.
        viewModelScope.launch {
            appSettings.flow.collect { s ->
                // Off → on: drop the sink's ARGB denoiser history, otherwise the first filtered
                // frame blends with a picture from before denoise was switched back on.
                if (s.denoise && lastAppliedDenoise == false) currentSink?.resetDenoisers()
                lastAppliedDenoise = s.denoise
                applier?.apply(s)
                _ui.update { it.copy(showStats = s.showStats) }
            }
        }
    }

    /** Bumps the haptic tick, unless the user turned cable-button vibration off. */
    private fun hapticTick() {
        if (appSettings.flow.value.haptics) _ui.update { it.copy(hapticTick = it.hapticTick + 1) }
    }

    /**
     * The common prelude to every cable-button event: bring the live view forward (see
     * [screenAfterButtonEvent]) and buzz. Called from the session worker thread, which
     * [MutableStateFlow] is safe for.
     *
     * The screen change also drops any pending [UiState.message]: the snackbar host only exists on
     * the live view, so a message raised while the user was elsewhere would be replayed — stale —
     * the moment they came back. This event's own message (if any) is published after it.
     */
    private fun onButtonEvent() {
        _ui.update {
            val next = screenAfterButtonEvent(it.screen)
            if (next == it.screen) it else it.copy(screen = next, returnTo = Screen.Live, message = null)
        }
        hapticTick()
    }

    /**
     * A per-session [FrameSink]. [attach] mints a fresh one (with its own [FrameBitmaps]) for
     * every [ScopeSession] it creates; [token] lets it recognize when its session has been swapped
     * out (e.g. by a replay start/stop) and ignore any frames/button events still in flight from
     * the old session's worker thread.
     */
    private inner class SessionSink(private val token: Long, private val bitmaps: FrameBitmaps) : FrameSink {
        // Debug-only timing: per-second summary of frame gaps and per-frame processing cost.
        private var lastFrameNanos = 0L
        private var windowStartNanos = 0L
        private var windowFrames = 0
        private var windowMaxGapMs = 0L
        private var windowProcessNanos = 0L
        private val argbDenoiser = TemporalDenoiser()
        /**
         * The sharpener for decoded JPEG frames. Its strength follows the session's, which the
         * settings collector keeps up to date. YUYV frames arrive already sharpened by
         * [ScopeSession], so they must not be run through this one as well.
         */
        private val argbSharpener = Sharpener()

        override fun onFrame(frame: Frame, state: SessionState) {
            if (token != currentSessionToken) return
            val t0 = System.nanoTime()
            lastFrame = frame.data
            val jpeg = frame.data is FrameData.Jpeg
            val denoiser = if (state.denoise && jpeg) argbDenoiser else null
            val sharpener = if (state.sharpen && jpeg) argbSharpener.also { it.strength = state.sharpenStrength } else null
            val bmp = bitmaps.toBitmap(frame.data, denoiser, sharpener) ?: return
            val shown = bitmaps.transform(bmp, state.rotation, state.mirror)
            lastImage = shown
            synchronized(recorderLock) {
                recorder?.let { rec -> runCatching { rec.record(shown) }.onFailure { stopRecordingLocked(keep = true) } }
            }
            _ui.update { it.copy(image = shown) }
            if (BuildConfig.DEBUG && _logFrameTiming.value) logTiming(t0, state)
        }

        /**
         * Drops the current timing window. Called when the log is switched back on, so the first
         * window after the gap does not report the whole off period as one enormous frame gap.
         * Best effort: these counters belong to the worker thread and are only ever read there, so
         * racing it costs at worst one skewed line in a debug log.
         */
        fun resetTiming() {
            lastFrameNanos = 0L; windowStartNanos = 0L; windowFrames = 0; windowMaxGapMs = 0L; windowProcessNanos = 0L
        }

        private fun logTiming(t0: Long, state: SessionState) {
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) windowMaxGapMs = maxOf(windowMaxGapMs, (t0 - lastFrameNanos) / 1_000_000)
            lastFrameNanos = t0
            windowFrames++
            windowProcessNanos += now - t0
            if (windowStartNanos == 0L) windowStartNanos = t0
            if (now - windowStartNanos >= 1_000_000_000L) {
                val secs = (now - windowStartNanos) / 1e9
                Log.d(
                    LOG_TAG,
                    String.format(
                        Locale.ROOT,
                        "timing: %.1f fps  maxGap %d ms  avgProcess %.1f ms  partial %d  dropped %d  driverFps %.1f",
                        windowFrames / secs, windowMaxGapMs, windowProcessNanos / 1e6 / windowFrames,
                        state.stats.framesPartial, state.stats.framesDropped, state.stats.fps,
                    ),
                )
                windowStartNanos = now; windowFrames = 0; windowMaxGapMs = 0; windowProcessNanos = 0
            }
        }

        override fun onButtonSnapshot() {
            if (token != currentSessionToken) return
            onButtonEvent()
            snapshot()
        }

        override fun onButtonRecordToggle() {
            if (token != currentSessionToken) return
            onButtonEvent()
            viewModelScope.launch { toggleRecording(fromButton = true) }
        }

        /**
         * A new stream: drop the ARGB denoiser's history so the first decoded JPEG frame of this
         * device is never blended with the last frame of the previous one. (The YUV denoiser lives
         * in [ScopeSession] and resets itself.)
         */
        override fun onStreamStarted() {
            if (token != currentSessionToken) return
            argbDenoiser.reset()
        }

        /**
         * Drops the ARGB denoiser's history. Called from Main when denoise is switched back on, so
         * the first filtered frame is not blended with whatever was on screen before the toggle
         * (frames from the off period never reached the denoiser). [TemporalDenoiser.reset] only
         * clears references, and the worker re-seeds them on its next frame, so racing it costs at
         * worst one extra pass-through frame.
         */
        fun resetDenoisers() = argbDenoiser.reset()
    }

    /**
     * Cancels the previous state collector (if any), builds a fresh [ScopeSession] over [devices]
     * with its own [SessionSink] (and thus its own [FrameBitmaps] and identity token), stores it as
     * the current [session], starts it, and launches exactly one collector that mirrors its state
     * into [_ui]. The USB-permission device is surfaced only while a live (non-replay) session is
     * active.
     */
    private fun attach(devices: DeviceSource, replaying: Boolean) {
        stateJob?.cancel()
        val token = ++nextSessionToken
        currentSessionToken = token
        val rejectedSizeReported = AtomicBoolean(false)
        val sink = SessionSink(token, FrameBitmaps { width, height ->
            if (rejectedSizeReported.compareAndSet(false, true)) {
                message(R.string.status_frame_too_large_format, width, height)
            }
        })
        currentSink = sink
        val newSession = ScopeSession(devices, DriverRegistry.all, viewModelScope, sink = sink)
        session = newSession
        // Before start(): a fresh session carries none of the user's settings, and the per-driver
        // rotation/mirror overrides have to be in place before the first device opens, or the first
        // picture of this session comes up in the driver's own default orientation.
        val newApplier = SettingsApplier(SessionSettingsTarget(newSession))
        newApplier.apply(appSettings.flow.value)
        applier = newApplier
        _ui.update { it.copy(replaying = replaying) }
        newSession.start()
        stateJob = viewModelScope.launch {
            newSession.state.collect { s ->
                if (!replaying) usbDiagnosticTraceStatus(s.connection, s.lastUsbError)?.let {
                    UsbEventTrace.recordState("viewer", it)
                }
                val streaming = s.connection is ConnectionState.Streaming
                // Anything but Streaming means the last frame is stale (unplug, error, reconnect):
                // drop it so the view falls back to its status text instead of a frozen picture.
                if (!streaming) { lastImage = null; lastFrame = null }
                _ui.update {
                    it.copy(
                        session = s,
                        image = if (streaming) it.image else null,
                        permissionDevice = if (!replaying && s.connection is ConnectionState.Failed) usbDevices.pendingPermission else null,
                    )
                }
                // Off the main thread: stopping the recorder takes recorderLock and calls into
                // MediaRecorder/MediaStore, neither of which is quick.
                if (!s.recording && recorder != null) viewModelScope.launch(Dispatchers.IO) { stopRecordingIfActive() }
            }
        }
    }

    // ---- actions (main thread) ----
    fun rotate() { session.rotate(); rememberOrientation() }
    fun toggleMirror() { session.toggleMirror(); rememberOrientation() }
    fun toggleStats() = appSettings.update { it.copy(showStats = !it.showStats) }
    /**
     * Switches destination, remembering where [Screen.Trust] was entered from so its back arrow can
     * return there (see [UiState.returnTo]), and dropping any pending [UiState.message]: the
     * snackbar host lives on the live view only, so a message the user has navigated away from
     * would otherwise be shown on their return, long after the event it describes.
     */
    fun navigate(screen: Screen) = _ui.update {
        it.copy(
            screen = screen,
            returnTo = if (screen == Screen.Trust) it.screen else Screen.Live,
            message = null,
        )
    }

    /** Replaces the whole settings object (the settings screen edits a copy and hands it back). */
    fun updateSettings(next: Settings) = appSettings.update { next }

    /** The first-launch tips card was dismissed; Settings can bring it back. */
    fun dismissTips() = appSettings.update { it.copy(tipsDismissed = true) }

    /**
     * Records the orientation the user just chose as this device's default, so the next time the
     * same endoscope is plugged in its picture comes up the right way round. Reads the session's
     * state rather than the requested change: [ScopeSession.rotate] and
     * [ScopeSession.toggleMirror] are no-ops while recording, and a refused change must not be
     * remembered. Nothing is written while no device is streaming (no driver id to file it under)
     * or when the value is already the stored one.
     */
    private fun rememberOrientation() {
        val s = session.state.value
        val driverId = s.driverId ?: return
        val next = DriverDefault(s.rotation, s.mirror)
        if (appSettings.flow.value.defaults[driverId] == next) return
        appSettings.update { it.copy(defaults = it.defaults + (driverId to next)) }
    }
    /**
     * Denoise is a persisted preference, so the toggle writes the store and the settings collector
     * applies it to the session (and resets the sink's ARGB denoiser on an off→on transition).
     */
    fun toggleDenoise() = appSettings.update { it.copy(denoise = !it.denoise) }

    fun snapshot() {
        // lastFrame and lastImage are both published from onFrame but may reflect adjacent frames
        // if a new frame lands between the two reads below; each branch below uses only one of them,
        // so that possible skew never matters.
        val data = lastFrame ?: return
        val shown = lastImage ?: return
        val s = session.state.value
        val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".jpg"
        // The blink fires now, not when the file lands: the shutter has to answer the tap, and the
        // save takes as long as MediaStore takes.
        _ui.update { it.copy(flashTick = it.flashTick + 1) }
        // Identifies this snapshot's thumbnail, so a later one's three-second timer cannot clear a
        // newer thumbnail than the one it was started for. Atomic: cable-button snapshots arrive on
        // the session's worker thread, on-screen ones on Main.
        val thumbGeneration = snapshotGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                when {
                    // A snapshot saves what the user sees. With denoise or sharpen on that is the
                    // filtered picture, which only exists as pixels, so both frame kinds go through
                    // the bitmap (re-encoded JPEG at quality 92, rotation/mirror already baked in).
                    s.denoise || s.sharpen -> saver.saveBitmapJpeg(shown, name)
                    // Original bytes plus an EXIF orientation tag: no re-encode, no quality loss.
                    data is FrameData.Jpeg -> saver.saveJpeg(data.bytes, ExifOrientation.of(s.rotation, s.mirror), name)
                    // Compress the bitmap the user is looking at. Never re-convert: FrameBitmaps
                    // reuses its pixel buffer for the worker's next frame. Every bitmap handed to
                    // the UI is freshly allocated per frame and never written to again once
                    // published, so compressing here while the worker moves on is safe.
                    else -> saver.saveBitmapJpeg(shown, name)
                }
            }.onSuccess {
                session.markSaved(name)
                // Scaling touches the bitmap's pixels; do it here on IO, not on Main. `shown` was
                // published to the UI and is never written again, so reading it is safe.
                val thumb = runCatching { thumbnailOf(shown) }.getOrNull()
                if (thumb != null) {
                    _ui.update { it.copy(lastSnapshotThumb = thumb) }
                    viewModelScope.launch {
                        delay(SNAPSHOT_TOAST_MILLIS)
                        // A newer snapshot owns the thumbnail now; leave it its full three seconds.
                        if (snapshotGeneration.get() == thumbGeneration) _ui.update { it.copy(lastSnapshotThumb = null) }
                    }
                }
            }.onFailure { e -> message(R.string.error_snapshot_failed_format, e.message.orEmpty()) }
        }
    }

    /**
     * A copy of [src] scaled to fit inside [THUMB_MAX_PX] on its longer side, aspect preserved.
     * Returns null for a degenerate bitmap rather than letting `createScaledBitmap` throw.
     */
    private fun thumbnailOf(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val scale = minOf(1f, THUMB_MAX_PX.toFloat() / maxOf(w, h).toFloat())
        val tw = maxOf(1, (w * scale).toInt())
        val th = maxOf(1, (h * scale).toInt())
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    /**
     * Guards a start or stop transition in flight so a second tap (e.g. a fast double-tap on
     * Stop) is ignored instead of racing the in-progress one. Set on Main before launching the
     * transition, cleared in a `finally` once the launched coroutine finishes.
     */
    @Volatile private var recordingTransition = false

    /**
     * @param fromButton true when this toggle was requested by the cable button (as opposed to the
     * on-screen Record button), in which case the resulting start/stop gets a transient
     * "Recording started/stopped" message; the on-screen button already shows its own state and
     * stays silent.
     */
    fun toggleRecording(fromButton: Boolean = false) {
        if (recordingTransition) return
        val active = recorder != null
        recordingTransition = true
        // Both start and stop touch MediaRecorder/MediaStore (and, for start, SurfaceRecorder
        // construction); keep all of it off the main thread.
        if (active) {
            viewModelScope.launch(Dispatchers.IO) {
                try { stopRecordingIfActive(fromButton) } finally { recordingTransition = false }
            }
            return
        }
        // The spinner goes up before the IO hop and comes down in the finally below, whichever way
        // the start ends — published, refused or thrown.
        _ui.update { it.copy(recordingStarting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = lastImage ?: return@launch
                val generation = synchronized(recorderLock) { recorderGeneration }
                val name = "SCOPE_" + LocalDateTime.now().format(STAMP) + ".mp4"
                val video = runCatching { saver.createVideo(name) }.getOrElse { e -> message(R.string.error_recording_start_failed_format, e.message.orEmpty()); return@launch }
                val created = runCatching { SurfaceRecorder(getApplication(), video.fd, img.width, img.height) }
                    .getOrElse { e -> saver.finishVideo(video, keep = false); message(R.string.error_recording_start_failed_format, e.message.orEmpty()); return@launch }
                synchronized(recorderLock) {
                    if (recorder != null || recorderGeneration != generation) { runCatching { created.close() }; saver.finishVideo(video, keep = false); return@launch }
                    recorder = created
                    pendingVideo = video
                    // Published while still holding recorderLock so the per-frame state collector
                    // can never observe recorder != null with recording still false and auto-stop
                    // the recording that was just started.
                    session.setRecording(true)
                    // The REC badge counts from here: the moment the encoder actually took over.
                    _ui.update { it.copy(recordingStartedAtNanos = System.nanoTime()) }
                }
                if (fromButton) message(R.string.status_recording_started)
                // "Saved" is reported by stopRecordingLocked, once the clip is actually finalised and kept.
            } finally {
                recordingTransition = false
                _ui.update { it.copy(recordingStarting = false) }
            }
        }
    }

    private fun stopRecordingIfActive(fromButton: Boolean = false) =
        synchronized(recorderLock) { stopRecordingLocked(keep = true, fromButton = fromButton) }

    private fun stopRecordingLocked(keep: Boolean, fromButton: Boolean = false) {
        val rec = recorder ?: return
        val video = pendingVideo
        recorder = null
        pendingVideo = null
        recorderGeneration++
        runCatching { rec.close() }
        if (video != null) {
            // A clip is only playable when frames were written AND the encoder stopped cleanly
            // (a failed stop means the MP4 was never finalised).
            val kept = keep && rec.framesWritten > 0 && !rec.stopFailed
            saver.finishVideo(video, keep = kept)
            if (kept) session.markSaved(video.displayName)
        }
        session.setRecording(false)
        _ui.update { it.copy(recordingStartedAtNanos = null) }
        if (fromButton) message(R.string.status_recording_stopped)
    }

    // ---- USB events from the activity ----
    fun onDeviceDetached(deviceName: String) {
        usbDevices.onDetached(deviceName)
        _ui.update { if (it.permissionDevice?.deviceName == deviceName) it.copy(permissionDevice = null) else it }
    }

    fun onPermissionResult(
        deviceName: String,
        granted: Boolean,
        deniedMessage: String? = null,
    ) {
        // A denial leaves the same attachment available for an explicit retry. Do not depend
        // on the next Connecting/Failed poll reaching Main: StateFlow can conflate those states.
        if (granted) usbDevices.clearPermissionRequest(deviceName)
        val denied = if (granted) null else deniedMessage
            ?: getApplication<Application>().getString(R.string.status_permission_denied)
        _ui.update {
            it.copy(
                permissionDevice = if (granted && it.permissionDevice?.deviceName == deviceName) null else it.permissionDevice,
                message = denied,
                messageTick = if (denied == null) it.messageTick else it.messageTick + 1,
            )
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    // ---- debug replay ----
    fun startReplay() {
        if (!BuildConfig.DEBUG || _ui.value.replaying || switchingSession) return
        val app = getApplication<Application>()
        switchSession {
            val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
            val replay = ReplayDeviceSource({ app.assets.open("fixtures/i4season-yuv-320x240.upkt") }, info, videoEndpoint = I4seasonYuvDriver.EP_IN)
            attach(replay, replaying = true)
        }
    }

    fun stopReplay() {
        if (!_ui.value.replaying || switchingSession) return
        switchSession { attach(usbDevices, replaying = false) }
    }

    /** Start or stop the replay fixture, whichever the current state calls for (developer sheet). */
    fun toggleReplay() {
        if (_ui.value.replaying) stopReplay() else startReplay()
    }

    /** Switches the per-second frame-timing log on or off (developer sheet, debug builds only). */
    fun setLogFrameTiming(on: Boolean) {
        if (_logFrameTiming.value == on) return
        _logFrameTiming.value = on
        // Switching it back on starts a fresh window, so the gap while it was off is not reported
        // as one enormous inter-frame delay.
        if (on) currentSink?.resetTiming()
    }

    /**
     * A short technical dump for a bug report: build, driver, connection and stream statistics.
     * Sampled when asked for, not maintained; the developer sheet copies it to the clipboard.
     * The driver's *display* name leads, with the raw id after it — this is the one place the id
     * itself is worth showing, and the sheet only exists in debug builds.
     */
    fun diagnostics(): String {
        val app = getApplication<Application>()
        val u = _ui.value
        val s = u.session
        val driver = app.getString(
            R.string.dev_diagnostics_driver_format,
            app.getString(driverNameRes(s.driverId)),
            s.driverId.orEmpty(),
        )
        val connection = when (val c = s.connection) {
            is ConnectionState.NoDevice -> app.getString(R.string.status_no_device)
            is ConnectionState.Connecting -> app.getString(R.string.status_connecting)
            is ConnectionState.Streaming ->
                if (u.replaying) app.getString(R.string.status_replaying) else app.getString(R.string.status_streaming)
            is ConnectionState.Failed -> app.getString(R.string.status_error_prefix, c.message)
        }
        val stats = app.getString(
            R.string.stats_overlay_format,
            s.stats.fps, s.stats.framesEmitted, s.stats.framesPartial, s.stats.framesDropped, s.stats.bytesReceived / 1e6,
        )
        return app.getString(
            R.string.dev_diagnostics_format,
            app.getString(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA,
            driver, connection, stats,
        )
    }

    /**
     * Tears the current session down off the main thread, then runs [start] (which calls [attach])
     * back on Main. Nothing here may block Main: the session join waits on an in-flight USB read.
     */
    private fun switchSession(start: () -> Unit) {
        switchingSession = true
        viewModelScope.launch {
            try {
                val joined = withContext(Dispatchers.IO) { session.stopAndJoin() }
                if (!joined) Log.w(LOG_TAG, "session did not stop in time")
                lastImage = null
                lastFrame = null
                start()
                _ui.update { it.copy(image = null) }
            } finally {
                switchingSession = false
            }
        }
    }

    override fun onCleared() {
        // The session join blocks until the current USB read returns; never on Main.
        val closing = session
        thread(name = "scope-shutdown") {
            stopRecordingIfActive()
            closing.stop()
        }
        super.onCleared()
    }

    /**
     * Hands the view model its [AppSettings]. Plain and explicit rather than a DI framework or an
     * `Application` subclass: there is exactly one dependency and one activity.
     */
    class Factory(private val app: Application, private val settings: AppSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ScopeViewModel(app, settings) as T
    }

    companion object {
        /** logcat tag for this view model, including the frame-timing log the developer sheet names. */
        const val LOG_TAG = "ScopeViewModel"
        /** Longest side of the snapshot thumbnail carried in [UiState.lastSnapshotThumb]. */
        private const val THUMB_MAX_PX = 96
        /** How long the "Saved" toast (and its thumbnail) stays up. */
        private const val SNAPSHOT_TOAST_MILLIS = 3_000L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
    }
}
