package com.technicallyvu.scope.usb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsbEventTraceTest {
    @org.junit.jupiter.api.Test
    fun `a real detach broadcast advances the generation even when enumeration misses the gap`() {
        val device = UsbPermissionDevice("/dev/bus/usb/001/002", 1002, 0x349c, 0x0411)
        val log = UsbEventLog()
        log.observeAttachments(listOf(UsbObservedAttachment(device, true)))
        org.junit.jupiter.api.Assertions.assertEquals("A1 349C:0411", log.label(device))
        log.onDetached(device)
        log.observeAttachments(listOf(UsbObservedAttachment(device, false)))
        org.junit.jupiter.api.Assertions.assertEquals("A2 349C:0411", log.label(device))
        org.junit.jupiter.api.Assertions.assertTrue(log.exportText().contains("detach broadcast: A1 349C:0411"))
        org.junit.jupiter.api.Assertions.assertFalse(log.exportText().contains("/dev/"))
    }

    private val camera = UsbPermissionDevice("/dev/bus/usb/001/002", 1002, 0x349c, 0x0411)

    @Test
    fun `unchanged polls do not bury grant and revocation transitions`() {
        var now = 1000L
        val log = UsbEventLog(clockMillis = { now })
        log.observeAttachments(listOf(UsbObservedAttachment(camera, false)))
        repeat(50) { now += 1000; log.observeAttachments(listOf(UsbObservedAttachment(camera, false))) }
        log.observeAttachments(listOf(UsbObservedAttachment(camera, true)))
        now += 10
        log.observeAttachments(listOf(UsbObservedAttachment(camera, false)))
        val report = log.exportText()
        assertEquals(4, report.lines().size)
        assertTrue(report.contains("+0ms attach: A1 349C:0411; USB permission=false"))
        assertTrue(report.contains("USB permission=false -> true"))
        assertTrue(report.contains("USB permission=true -> false"))
        assertFalse(report.contains("/dev/"))
        assertFalse(report.contains("1002"))
    }

    @Test
    fun `an observed detach and reused Android identity receive a new attachment generation`() {
        val log = UsbEventLog(clockMillis = { 0 })
        log.observeAttachments(listOf(UsbObservedAttachment(camera, true)))
        assertEquals("A1 349C:0411", log.label(camera))
        log.observeAttachments(emptyList())
        assertEquals("unobserved 349C:0411", log.label(camera))
        log.recordDevice("late callback", camera, "reportedGranted=true")
        log.observeAttachments(listOf(UsbObservedAttachment(camera, false)))
        assertEquals("A2 349C:0411", log.label(camera))
        val report = log.exportText()
        assertTrue(report.contains("detach: A1 349C:0411"))
        assertTrue(report.contains("late callback: unobserved 349C:0411; reportedGranted=true"))
        assertTrue(report.contains("attach: A2 349C:0411; USB permission=false"))
    }

    @Test
    fun `a changed USB address reenumerates even when the model code is unchanged`() {
        val log = UsbEventLog(clockMillis = { 0 })
        val replacement = camera.copy(name = "/dev/bus/usb/001/003", id = 1003)
        log.observeAttachments(listOf(UsbObservedAttachment(camera, true)))
        log.observeAttachments(listOf(UsbObservedAttachment(replacement, false)))
        val report = log.exportText()
        assertTrue(report.indexOf("detach: A1") < report.indexOf("attach: A2"))
        assertEquals("A2 349C:0411", log.label(replacement))
        assertFalse(report.contains("/dev/"))
    }

    @Test
    fun `history stays bounded and sanitizes untrusted callback strings`() {
        var now = 0L
        val log = UsbEventLog(maxEntries = 3, clockMillis = { now })
        repeat(10) { now++; log.record("step", "attempt=$it") }
        log.record("reply\nforged", "from /dev/bus/usb/001/002\n" + "x".repeat(1000))
        val report = log.exportText()
        assertEquals(4, report.lines().size)
        assertFalse(report.contains("attempt=0"))
        assertTrue(report.contains("attempt=9"))
        assertTrue(report.contains("reply forged: from [USB device] "))
        assertFalse(report.contains("/dev/"))
        assertTrue(report.lines().last().length < 350)
    }

    @Test
    fun `periodic state coalescing does not hide a changed result or separate explicit user attempt`() {
        val log = UsbEventLog(clockMillis = { 0 })
        log.observeAttachments(listOf(UsbObservedAttachment(camera, false)))
        repeat(50) {
            log.recordDeviceState("open", camera, "permission=false")
            log.recordState("camera", "permission=true")
        }
        log.recordDevice("request", camera, "user tapped Allow USB access")
        log.recordDeviceState("open", camera, "permission=true")
        val report = log.exportText()
        assertEquals(6, report.lines().size)
        assertEquals(1, report.lines().count { it.contains("open A1 349C:0411: permission=false") })
        assertTrue(report.contains("request: A1 349C:0411; user tapped Allow USB access"))
        assertTrue(report.contains("open A1 349C:0411: permission=true"))
    }

    @Test
    fun `permission inspection errors remain distinct from denial and relative time cannot go negative`() {
        var now = 100L
        val log = UsbEventLog(clockMillis = { now })
        now = 99
        log.observeAttachments(listOf(UsbObservedAttachment(camera, null)))
        log.observeAttachments(listOf(UsbObservedAttachment(camera, false)))
        assertTrue(log.exportText().contains("+0ms attach"))
        assertTrue(log.exportText().contains("USB permission=unknown -> false"))
    }
}
