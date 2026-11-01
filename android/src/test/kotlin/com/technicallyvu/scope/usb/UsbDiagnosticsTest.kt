package com.technicallyvu.scope.usb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsbDiagnosticsTest {
    private fun input(type: Int = 2, address: Int = 0x81) = UsbDiagnosticEndpoint(address, type, 0x80, 512, 1)
    private fun stream(alt: Int, endpoints: List<UsbDiagnosticEndpoint>) =
        UsbDiagnosticInterface(1, alt, 0x0e, 0x02, 0, endpoints)

    private fun camera(
        usbPermission: Boolean? = true,
        drivers: List<String> = listOf("uvc-bulk"),
        interfaces: List<UsbDiagnosticInterface> = listOf(stream(0, listOf(input()))),
    ) = UsbDiagnosticDevice(0x2ce3, 0x3828, 0xef, 2, 1, usbPermission, true, drivers, interfaces)

    private fun snapshot(
        devices: List<UsbDiagnosticDevice> = emptyList(),
        cameraGranted: Boolean = true,
        enumerationError: String? = null,
    ) = UsbDiagnosticSnapshot("test", 5, "abc123", 37, "17", "Motorola", "moto g86", "BUILD", true, cameraGranted, devices, enumerationError)

    @Test
    fun `classes and endpoints contain both symbolic and numeric descriptor fields`() {
        assertEquals("0x0E (VIDEO)", usbClassSummary(0x0e))
        assertEquals("0xFF (VENDOR_SPECIFIC)", usbClassSummary(0xff))
        assertEquals("0x42 (OTHER)", usbClassSummary(0x42))
        assertEquals("EP 0x81: type=BULK(2), direction=IN(0x80), maxPacketSize=512, interval=1", usbEndpointSummary(input()))
        assertTrue(usbEndpointSummary(input(1)).contains("ISOCHRONOUS(1)"))
        assertTrue(usbEndpointSummary(UsbDiagnosticEndpoint(0x02, 3, 0, 64, 4)).contains("direction=OUT(0x00)"))
    }

    @Test
    fun `zero enumerated devices differs from a failed enumeration`() {
        val zero = formatUsbDiagnostics(snapshot())
        assertTrue(zero.contains("Attached USB devices: 0"))
        assertTrue(zero.contains("Driver selection has not started"))
        val error = formatUsbDiagnostics(snapshot(enumerationError = "SecurityException"))
        assertTrue(error.contains("USB enumeration: ERROR (SecurityException)"))
        assertTrue(error.contains("Attached USB devices: unknown"))
        assertFalse(error.contains("zero USB devices"))
    }

    @Test
    fun `attached but unmatched camera is diagnosed separately from missing permission`() {
        val unsupported = formatUsbDiagnostics(snapshot(listOf(camera(drivers = emptyList()))))
        assertTrue(unsupported.contains("Attached USB devices: 1"))
        assertTrue(unsupported.contains("DriverRegistry matches (alt 0): none"))
        assertTrue(unsupported.contains("unsupported by the current driver matching rules"))
        val permission = formatUsbDiagnostics(snapshot(listOf(camera(usbPermission = false))))
        assertTrue(permission.contains("USB permission is missing"))
        val cameraPermission = formatUsbDiagnostics(snapshot(listOf(camera()), cameraGranted = false))
        assertTrue(cameraPermission.contains("CAMERA permission is missing"))
    }

    @Test
    fun `isochronous endpoint in a nonzero alternate setting is identified`() {
        val interfaces = listOf(stream(0, emptyList()), stream(1, listOf(input(1))))
        val report = formatUsbDiagnostics(snapshot(listOf(camera(interfaces = interfaces))))
        assertTrue(report.contains("Interface 1, alt 0"))
        assertTrue(report.contains("Interface 1, alt 1"))
        assertTrue(report.contains("native isochronous transport unavailable on this build"))
        assertTrue(report.contains("EP 0x81: type=ISOCHRONOUS(1)"))
        val enabled = formatUsbDiagnostics(snapshot(listOf(camera(interfaces = interfaces))).copy(isochronousSupported = true))
        assertTrue(enabled.contains("Native isochronous transport available: true"))
        assertTrue(enabled.contains("native isochronous transport available, format/handshake not tested"))
    }

    @Test
    fun `only bulk input on a VideoStreaming interface counts as a UVC bulk candidate`() {
        val control = UsbDiagnosticInterface(0, 0, 0x0e, 1, 0, listOf(input()))
        val output = input().copy(address = 1, direction = 0)
        assertTrue(usbVideoTransferSummary(listOf(control, stream(0, listOf(output)))).contains("no bulk or isochronous IN"))
        assertTrue(usbVideoTransferSummary(listOf(stream(0, listOf(input())))).contains("bulk candidate, format/handshake not tested"))
        val vendor = UsbDiagnosticInterface(0, 0, 0xff, 0xf0, 1, listOf(input()))
        assertTrue(usbVideoTransferSummary(listOf(vendor)).contains("a matching vendor driver is required"))
    }

    @Test
    fun `report has stable interface ordering and no device path or serial fields`() {
        val interfaces = listOf(stream(2, listOf(input(address = 0x83), input(address = 0x81))), stream(0, emptyList()))
        val report = formatUsbDiagnostics(snapshot(listOf(camera(interfaces = interfaces))))
        assertTrue(report.indexOf("Interface 1, alt 0") < report.indexOf("Interface 1, alt 2"))
        assertTrue(report.indexOf("EP 0x81") < report.indexOf("EP 0x83"))
        assertTrue(report.contains("2CE3:3828"))
        assertTrue(report.contains("no serial numbers"))
        assertFalse(report.contains("/dev/"))
        assertFalse(report.contains("serialNumber="))
    }

    @Test
    fun `OS and model fields cannot inject fake diagnostic lines`() {
        val report = formatUsbDiagnostics(snapshot().copy(model = "moto g86\nResult: fake\tvalue"))
        assertTrue(report.contains("Device: Motorola moto g86 Result: fake value"))
        assertFalse(report.contains("\nResult: fake"))
    }
}
