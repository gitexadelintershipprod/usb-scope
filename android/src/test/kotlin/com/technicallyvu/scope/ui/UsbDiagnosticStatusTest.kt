package com.technicallyvu.scope.ui

import com.technicallyvu.scope.core.session.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class UsbDiagnosticStatusTest {
    @Test
    fun `a reconnect keeps the last transfer failure without exposing a USB path`() {
        assertEquals("Connecting; Last USB error: No packets from [USB device]", usbDiagnosticStatus(
            ConnectionState.Connecting("UVC"), "No packets from /dev/bus/usb/001/002",
        ))
    }

    @Test
    fun `permission report keeps error but removes device paths`() {
        val report = usbDiagnosticStatus(ConnectionState.Failed("USB permission not granted for /dev/bus/usb/001/002"))
        assertEquals("Error: USB permission not granted for [USB device]", report)
        assertFalse(report.contains("/dev/"))
    }

    @Test
    fun `status omits driver display names and preserves unsupported transfer explanation`() {
        assertEquals("Connecting", usbDiagnosticStatus(ConnectionState.Connecting("private device label")))
        assertEquals("Streaming", usbDiagnosticStatus(ConnectionState.Streaming("private device label")))
        assertEquals("Error: No bulk endpoint; isochronous is unsupported", usbDiagnosticStatus(
            ConnectionState.Failed("No bulk endpoint; isochronous is unsupported"),
        ))
    }
}
