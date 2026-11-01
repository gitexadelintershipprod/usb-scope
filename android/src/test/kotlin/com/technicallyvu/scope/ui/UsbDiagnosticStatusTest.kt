package com.technicallyvu.scope.ui

import com.technicallyvu.scope.core.session.ConnectionState
import com.technicallyvu.scope.usb.UsbEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class UsbDiagnosticStatusTest {
    @Test
    fun `minutes of repeated permission failure retain the request and callback`() {
        val log = UsbEventLog(clockMillis = { 0 })
        val denied = "USB permission not granted for /dev/bus/usb/001/002"
        log.record("USB permission requested", "test attachment")
        log.record("USB permission reply", "reported=false")
        repeat(600) {
            for (state in listOf(ConnectionState.Connecting("UVC"), ConnectionState.Failed(denied))) {
                usbDiagnosticTraceStatus(state, denied)?.let { log.recordState("viewer", it) }
            }
        }
        val report = log.exportText()
        assertEquals(4, report.lines().size)
        org.junit.jupiter.api.Assertions.assertTrue(report.contains("USB permission requested"))
        org.junit.jupiter.api.Assertions.assertTrue(report.contains("USB permission reply"))
        assertFalse(report.contains("/dev/"))
        usbDiagnosticTraceStatus(ConnectionState.Streaming("UVC"))?.let { log.recordState("viewer", it) }
        usbDiagnosticTraceStatus(ConnectionState.Failed("New failure"), "New failure")?.let { log.recordState("viewer", it) }
        org.junit.jupiter.api.Assertions.assertTrue(log.exportText().contains("viewer: Streaming"))
        org.junit.jupiter.api.Assertions.assertTrue(log.exportText().contains("viewer: Error: New failure"))
    }

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
