package com.technicallyvu.scope.ui

import com.technicallyvu.scope.core.session.ConnectionState

/** Retrying the same failure must not evict the permission request/reply from bounded history. */
fun usbDiagnosticTraceStatus(state: ConnectionState, lastUsbError: String? = null): String? =
    if (state is ConnectionState.Connecting && lastUsbError != null) null
    else usbDiagnosticStatus(state, lastUsbError)

/** Keep the useful opening error without copying an ephemeral Android USB device path. */
fun usbDiagnosticStatus(state: ConnectionState, lastUsbError: String? = null): String {
    val current = when (state) {
        is ConnectionState.NoDevice -> "No device selected"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Streaming -> "Streaming"
        is ConnectionState.Failed -> "Error: " + state.message
    }
    val withLast = if (lastUsbError != null && (state !is ConnectionState.Failed || state.message != lastUsbError))
        "$current; Last USB error: $lastUsbError" else current
    return withLast.replace(Regex("/dev/bus/usb/[^\\s,;\\)]+"), "[USB device]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
}
