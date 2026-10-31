package com.technicallyvu.scope.ui

import com.technicallyvu.scope.core.session.ConnectionState

/** Keep the useful opening error without copying an ephemeral Android USB device path. */
fun usbDiagnosticStatus(state: ConnectionState): String = when (state) {
    is ConnectionState.NoDevice -> "No device selected"
    is ConnectionState.Connecting -> "Connecting"
    is ConnectionState.Streaming -> "Streaming"
    is ConnectionState.Failed -> "Error: " + state.message
        .replace(Regex("/dev/bus/usb/[^\\s,;\\)]+"), "[USB device]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
}
