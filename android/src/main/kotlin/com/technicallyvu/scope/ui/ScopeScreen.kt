package com.technicallyvu.scope.ui

import android.hardware.usb.UsbDevice
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.technicallyvu.scope.BuildConfig
import com.technicallyvu.scope.R
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.ui.components.ControlsSheet
import com.technicallyvu.scope.ui.components.RecBadge
import com.technicallyvu.scope.ui.components.ShutterFlash
import com.technicallyvu.scope.ui.components.SnapshotToast
import com.technicallyvu.scope.ui.components.StatusChip
import com.technicallyvu.scope.ui.components.TipsCard
import com.technicallyvu.scope.ui.components.rememberShutterFlashAlpha
import com.technicallyvu.scope.ui.components.toConnStatus
import kotlinx.coroutines.delay

/** How long the controls stay up after the last interaction, while streaming. */
private const val AUTO_HIDE_MILLIS = 4_000L

/**
 * The most of the window height the bottom chrome (tips card, toast, permission button, snackbar,
 * sheet) may occupy before it starts scrolling. A landscape phone is ~360 dp tall, where the
 * undismissed tips card and the 126 dp sheet together overflow the window; the cap keeps the
 * "no picture" prompt a share of the screen instead of squeezing it to nothing.
 */
private const val BOTTOM_CHROME_MAX_FRACTION = 0.6f

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ScopeScreen(vm: ScopeViewModel, onRequestPermission: (UsbDevice) -> Unit) {
    val ui by vm.ui.collectAsState()
    var showUsbDiagnostics by remember { mutableStateOf(false) }
    if (showUsbDiagnostics) {
        UsbDiagnosticsDialog(
            status = usbDiagnosticStatus(ui.session.connection, ui.session.lastUsbError),
            onDismiss = { showUsbDiagnostics = false },
            onRequestPermission = onRequestPermission,
        )
    }
    // No activity (a preview, or a non-activity context): fall back to the compact layout rather
    // than crashing on a cast.
    val activity = LocalActivity.current
    val wide = if (activity == null) false else calculateWindowSizeClass(activity).widthSizeClass == WindowWidthSizeClass.Expanded

    // Cable-button feedback is hoisted above the destination switch below, because the view model
    // navigates back to the live view on every button event (ScopeViewModel.screenAfterButtonEvent)
    // and that rebuilds the live subtree. State remembered *inside* it would be re-seeded from the
    // already-bumped tick, so the press that brought the user back would arrive with its buzz and
    // its flash already spent. `interactions` rides along for the same reason: a snapshot has to
    // count as an interaction whichever screen it was taken from.
    var interactions by remember { mutableIntStateOf(0) }
    val onInteract: () -> Unit = remember { { interactions++ } }
    val haptic = LocalHapticFeedback.current
    var answeredHapticTick by remember { mutableLongStateOf(ui.hapticTick) }
    LaunchedEffect(ui.hapticTick) {
        if (ui.hapticTick == answeredHapticTick) return@LaunchedEffect
        answeredHapticTick = ui.hapticTick
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val flashAlpha = rememberShutterFlashAlpha(ui.flashTick, onFlash = onInteract)

    // Remembered, not `vm::navigate`: a method reference is a fresh instance on every
    // recomposition, and this screen recomposes per frame, which would recompose all of
    // TrustScreen (license text included) at the frame rate while it is open.
    val onBack = remember(vm) { { vm.navigate(Screen.Live) } }
    when (ui.screen) {
        Screen.Trust -> {
            // Null in a release build: this is the only thing that ever constructs a DevControls,
            // so with it gone the About screen attaches no long-press and DevSheet is never
            // called. (The class is still compiled into the release APK — minification is off —
            // but nothing in the UI path can reach it.)
            val devControls = remember(vm) {
                if (BuildConfig.DEBUG) {
                    DevControls(
                        state = vm.devState,
                        onToggleReplay = vm::toggleReplay,
                        onSetLogFrameTiming = vm::setLogFrameTiming,
                        diagnostics = vm::diagnostics,
                    )
                } else {
                    null
                }
            }
            // About is reachable from the live view and from Settings, and back has to undo
            // whichever one it was: `Screen` is flat, so the origin travels in UiState.returnTo.
            // Keyed on the value, not captured from `ui`, so the lambda stays stable while the
            // screen recomposes behind the About text.
            val returnTo = ui.returnTo
            TrustScreen(
                onBack = remember(vm, returnTo) { { vm.navigate(returnTo) } },
                devControls = devControls,
                onUsbDiagnostics = { showUsbDiagnostics = true },
            )
            return
        }
        Screen.Settings -> {
            // Same reasoning as onBack: stable callbacks (and an unchanged Settings instance) let
            // Compose skip the whole settings subtree while frames keep arriving behind it.
            val settings by vm.settings.collectAsState()
            SettingsScreen(
                settings = settings,
                onChange = remember(vm) { vm::updateSettings },
                onBack = onBack,
                onAbout = remember(vm) { { vm.navigate(Screen.Trust) } },
            )
            return
        }
        Screen.Live -> Unit
    }

    // The first-launch tips: up until "Got it" (or a reset from Settings), never over a recording —
    // the REC badge and the elapsed time matter more than advice by then.
    val settings by vm.settings.collectAsState()
    val showTips = !settings.tipsDismissed && !ui.session.recording
    val onDismissTips = remember(vm) { vm::dismissTips }

    // Auto-hide. Every interaction bumps `interactions`, which restarts the effect below; the
    // controls are only ever hidden while a stream is actually running, so a user staring at
    // "No device" is never left with a bare black screen.
    //
    // An undismissed tips card also holds the chrome up. Android launches this app *on device
    // attach*, so the one launch the card exists for can go NoDevice → Streaming in a couple of
    // seconds, and a four-second window to read four lines and find "Got it" defeats the point.
    // The card carries its own explicit dismissal, so it is allowed to own the timer until then;
    // one tap on "Got it" restores normal auto-hide behaviour for good.
    //
    // A snapshot from any source counts as an interaction too, so the chrome — and with it the
    // "Saved" toast — comes back for its own four seconds; without that, the primary flow (probe in
    // one hand, phone propped up, cable button pressed long after the chrome auto-hid) gets the
    // shutter flash and no confirmation at all. That bump is wired into
    // `rememberShutterFlashAlpha`'s onFlash above the destination switch, so it survives the
    // navigation a cable-button snapshot performs.
    val streaming = ui.session.connection is ConnectionState.Streaming
    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(streaming, interactions, showTips) {
        chromeVisible = true
        if (!streaming || showTips) return@LaunchedEffect
        delay(AUTO_HIDE_MILLIS)
        chromeVisible = false
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // Keyed on the tick, not the text: two identical messages in a row would otherwise leave the
    // key unchanged, so the second would never be shown (nor consumed).
    LaunchedEffect(ui.messageTick) {
        val msg = ui.message ?: return@LaunchedEffect
        // Suspends until the snackbar is dismissed (or a newer message cancels this effect, which
        // dismisses it for us); only then is the message consumed.
        snackbarHostState.showSnackbar(msg)
        vm.clearMessage()
    }

    // How much of the bottom of the screen the tips card, the toast and the sheet are using. The
    // "Plug in your endoscope." prompt is centred in what is left rather than in the whole window:
    // centred in the whole window, a 360 dp phone showing the four-line tips card drew the card
    // straight over the prompt.
    var bottomChromePx by remember { mutableIntStateOf(0) }
    val bottomChrome = with(LocalDensity.current) { bottomChromePx.toDp() }
    // Read from the configuration rather than measured with a BoxWithConstraints: this subtree
    // recomposes at the stream's frame rate, and a subcomposition per frame is exactly the cost the
    // rest of this screen was rewritten to avoid.
    val maxChromeHeight = (LocalConfiguration.current.screenHeightDp * BOTTOM_CHROME_MAX_FRACTION).dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A tap anywhere brings the controls back. Taps the buttons themselves consume never
            // reach this detector; those restart the timer through onInteract instead.
            .pointerInput(Unit) { detectTapGestures { interactions++ } },
    ) {
        // The picture is full-bleed: it is the whole point of the screen and the chrome floats over
        // it. Only the no-picture prompt below has to keep out of the chrome's way.
        ui.image?.let { img ->
            Image(
                bitmap = img.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_live_view),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (ui.image == null) {
                // Inside the inset box and padded by the measured chrome height, so "centred" means
                // centred in the space actually left over.
                NoPictureMessage(
                    ui,
                    Modifier.fillMaxSize().padding(bottom = bottomChrome),
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Primitives, not `ui`: see the ControlsSheet KDoc. fps is only passed (and so
                    // only changes the chip's arguments) while streaming with stats on.
                    StatusChip(
                        status = ui.session.connection.toConnStatus(),
                        errorMessage = (ui.session.connection as? ConnectionState.Failed)?.message,
                        replaying = ui.replaying,
                        fps = if (streaming && ui.showStats) ui.session.stats.fps else null,
                    )
                    // Only when there is a stream to have statistics about: with no device the
                    // overlay showed a permanent "0.0 fps  frames 0  partial 0  dropped 0  0.0 MB",
                    // which looks like a stalled stream rather than an absent one.
                    if (ui.showStats && (streaming || ui.replaying)) StatsOverlay(ui.session.stats)
                }
            }

            // Outside the fade on purpose: a clip being written must stay visible even after
            // everything else has gone.
            if (ui.session.recording) {
                RecBadge(ui.recordingStartedAtNanos, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(end = if (wide) 88.dp else 0.dp)
                    // A landscape phone is ~360 dp tall and still Medium-width, so it gets the
                    // compact sheet: the undismissed tips card (~200 dp) plus the 126 dp sheet does
                    // not fit, and unbounded the column overflowed upward over the status chip.
                    // Capped and scrolled instead — reversed, so offset zero is the *bottom* and
                    // the sheet, the only part with controls in it, is what stays put.
                    // Feeds the prompt's bottom padding above. Reported on every size change, so a
                    // dismissed tips card or a hidden sheet immediately gives the prompt its space
                    // back. It has to sit *outside* the two modifiers below: inside the scroll it
                    // would report the unclamped content height instead of the height actually on
                    // screen, and the prompt would be padded off the screen entirely.
                    .onSizeChanged { bottomChromePx = it.height }
                    .heightIn(max = maxChromeHeight)
                    .verticalScroll(rememberScrollState(), reverseScrolling = true),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = chromeVisible && showTips,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    TipsCard(onDismiss = onDismissTips, modifier = Modifier.padding(horizontal = 12.dp))
                }
                SnapshotToast(
                    thumb = ui.lastSnapshotThumb,
                    name = ui.session.lastSaved,
                    visible = chromeVisible,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                ui.permissionDevice?.let { device ->
                    Button(
                        onClick = { onInteract(); onRequestPermission(device) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.action_allow_usb_access)) }
                }
                if (!streaming) {
                    Button(
                        onClick = { onInteract(); showUsbDiagnostics = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text(stringResource(R.string.usb_diagnostics_title)) }
                }
                SnackbarHost(snackbarHostState)
                if (!wide) {
                    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                        Sheet(ui, wide = false, onInteract = onInteract, actions = rememberSheetActions(vm))
                    }
                }
            }

            if (wide) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Sheet(ui, wide = true, onInteract = onInteract, actions = rememberSheetActions(vm))
                }
            }
        }

        ShutterFlash(flashAlpha)
    }
}

/**
 * The sheet's callbacks, bundled so the compact sheet and the wide rail cannot drift apart.
 * Remembered per view model: the sheet is recomposed on every frame while a stream runs.
 */
@Composable
private fun rememberSheetActions(vm: ScopeViewModel): SheetActions = remember(vm) {
    SheetActions(
        onSnapshot = vm::snapshot,
        onToggleRecording = { vm.toggleRecording() },
        onRotate = vm::rotate,
        onToggleMirror = vm::toggleMirror,
        onToggleDenoise = vm::toggleDenoise,
        onToggleStats = vm::toggleStats,
        onSettings = { vm.navigate(Screen.Settings) },
        onAbout = { vm.navigate(Screen.Trust) },
    )
}

/** @see rememberSheetActions */
class SheetActions(
    val onSnapshot: () -> Unit,
    val onToggleRecording: () -> Unit,
    val onRotate: () -> Unit,
    val onToggleMirror: () -> Unit,
    val onToggleDenoise: () -> Unit,
    val onToggleStats: () -> Unit,
    val onSettings: () -> Unit,
    val onAbout: () -> Unit,
)

/**
 * Unpacks [UiState] into the stable primitives [ControlsSheet] takes. This wrapper still recomposes
 * on every frame (its `ui` parameter is a new instance each time), but it is a single pass-through
 * call: the sheet below it skips unless one of these booleans actually flipped, so the nine buttons
 * and their hand-drawn glyphs are no longer redrawn at the stream frame rate.
 */
@Composable
private fun Sheet(ui: UiState, wide: Boolean, onInteract: () -> Unit, actions: SheetActions) {
    ControlsSheet(
        // A live frame AND a live stream: after an unplug the last bitmap is dropped, and a stale
        // one must never leave Snapshot/Record enabled.
        streaming = ui.session.connection is ConnectionState.Streaming && ui.image != null,
        recording = ui.session.recording,
        recordingStarting = ui.recordingStarting,
        denoise = ui.session.denoise,
        showStats = ui.showStats,
        wide = wide,
        onInteract = onInteract,
        onSnapshot = actions.onSnapshot,
        onToggleRecording = actions.onToggleRecording,
        onRotate = actions.onRotate,
        onToggleMirror = actions.onToggleMirror,
        onToggleDenoise = actions.onToggleDenoise,
        onToggleStats = actions.onToggleStats,
        onSettings = actions.onSettings,
        onAbout = actions.onAbout,
    )
}

/**
 * The one line that explains why there is no picture. The caller places it in the space the bottom
 * chrome is not using, so it stays centred in what the user can actually see.
 */
@Composable
private fun NoPictureMessage(ui: UiState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val text = when (val c = ui.session.connection) {
            is ConnectionState.NoDevice -> stringResource(R.string.live_view_no_device)
            is ConnectionState.Connecting -> stringResource(R.string.live_view_connecting)
            is ConnectionState.Streaming -> stringResource(R.string.live_view_waiting_first_frame)
            is ConnectionState.Failed ->
                if (ui.permissionDevice != null) stringResource(R.string.live_view_permission_needed)
                else stringResource(R.string.live_view_error_format, c.message)
        }
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun StatsOverlay(st: StreamStats) {
    Text(
        stringResource(
            R.string.stats_overlay_format,
            st.fps, st.framesEmitted, st.framesPartial, st.framesDropped, st.bytesReceived / 1e6,
        ),
        color = Color(0xFF00FF66),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(Color(0x99000000)).padding(6.dp),
    )
}
