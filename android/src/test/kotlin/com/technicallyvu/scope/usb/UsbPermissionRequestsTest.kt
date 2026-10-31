package com.technicallyvu.scope.usb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsbPermissionRequestsTest {
    private val camera = UsbPermissionDevice("/dev/bus/usb/001/002", 1002, 0x1234, 0x5678)
    private fun request(token: String = "first", step: UsbPermissionStep = UsbPermissionStep.Usb) =
        UsbPermissionRequest(camera, token, step)

    @Test
    fun `video class at device or any interface requires camera permission`() {
        assertTrue(requiresCameraPermission(0x0e, emptyList()))
        assertTrue(requiresCameraPermission(0xef, listOf(0x01, 0x0e)))
        assertTrue(requiresCameraPermission(0, listOf(0xff, 0x0e, 0x03)))
        assertFalse(requiresCameraPermission(0xef, listOf(0xff)))
        assertFalse(requiresCameraPermission(0xff, emptyList()))
    }

    @Test
    fun `repeated taps cannot replace an outstanding USB request`() {
        val requests = UsbPermissionRequests()
        val first = request()
        assertTrue(requests.begin(first))
        assertFalse(requests.begin(request("second")))
        assertEquals(first, requests.pending)
    }

    @Test
    fun `USB results require matching token attachment and USB stage`() {
        val requests = UsbPermissionRequests()
        val first = request()
        requests.begin(first)
        assertNull(requests.usbResult("unrelated", camera))
        assertNull(requests.usbResult(null, camera))
        assertNull(requests.usbResult(first.token, null))
        assertNull(requests.usbResult(first.token, camera.copy(id = 9000)))
        assertNull(requests.usbResult(first.token, camera.copy(productId = 9000)))
        assertEquals(first, requests.usbResult(first.token, camera))
    }

    @Test
    fun `old grant cannot authorize a reconnected camera even if Android reuses its identity`() {
        val requests = UsbPermissionRequests()
        val old = request()
        requests.begin(old)
        assertEquals(old, requests.detach(camera.name))
        val current = request("new-attachment")
        assertTrue(requests.begin(current))
        assertNull(requests.usbResult(old.token, camera))
        assertFalse(requests.finish(old))
        assertEquals(current, requests.usbResult(current.token, camera))
    }

    @Test
    fun `camera gate blocks USB callbacks until runtime permission has completed`() {
        val requests = UsbPermissionRequests()
        val initial = request(step = UsbPermissionStep.Camera)
        requests.begin(initial)
        assertNull(requests.usbResult(initial.token, camera))
        assertEquals(initial, requests.externalResult(UsbPermissionStep.Camera))
        val usb = requests.startUsb(initial)!!
        assertEquals(UsbPermissionStep.Usb, usb.step)
        assertEquals(usb, requests.usbResult(initial.token, camera))
    }

    @Test
    fun `detached camera dialog cannot apply its answer to a new camera`() {
        val requests = UsbPermissionRequests()
        val initial = request(step = UsbPermissionStep.Camera)
        requests.begin(initial)
        requests.detach(camera.name)
        assertFalse(requests.begin(request("next", UsbPermissionStep.Camera)))
        assertNull(requests.externalResult(UsbPermissionStep.Camera))
        assertTrue(requests.begin(request("next", UsbPermissionStep.Camera)))
    }

    @Test
    fun `denial can be retried without blocking future prompts`() {
        val requests = UsbPermissionRequests()
        val initial = request(step = UsbPermissionStep.Camera)
        requests.begin(initial)
        assertEquals(initial, requests.externalResult(UsbPermissionStep.Camera))
        assertTrue(requests.finish(initial))
        assertTrue(requests.begin(request("retry", UsbPermissionStep.Settings)))
    }

    @Test
    fun `recreation preserves the active runtime dialog identity`() {
        val initial = request(step = UsbPermissionStep.Camera)
        val restored = UsbPermissionRequests(initial, initial.token)
        assertFalse(restored.begin(request("duplicate")))
        assertEquals(initial, restored.externalResult(UsbPermissionStep.Camera))
        assertEquals(initial.copy(step = UsbPermissionStep.Usb), restored.startUsb(initial))
    }

    @Test
    fun `unrelated detach and callback cannot consume settings request`() {
        val requests = UsbPermissionRequests()
        val initial = request(step = UsbPermissionStep.Settings)
        requests.begin(initial)
        assertNull(requests.detach("another-device"))
        assertNull(requests.externalResult(UsbPermissionStep.Camera))
        assertEquals(initial, requests.externalResult(UsbPermissionStep.Settings))
    }

    @Test
    fun `retry recovers a lost denied USB reply and rejects the original queued result`() {
        val initial = request()
        val restored = UsbPermissionRequests(initial)
        assertEquals(initial, restored.cancelUsbForRetry(camera))
        val retried = request("retry-after-recreation")
        assertTrue(restored.begin(retried))
        assertNull(restored.usbResult(initial.token, camera))
        assertEquals(retried, restored.usbResult(retried.token, camera))
    }

    @Test
    fun `USB retry cannot replace a camera dialog or another attached device`() {
        val initial = request(step = UsbPermissionStep.Camera)
        val requests = UsbPermissionRequests()
        requests.begin(initial)
        assertNull(requests.cancelUsbForRetry(camera))
        assertFalse(requests.begin(request("duplicate")))
        assertEquals(initial, requests.externalResult(UsbPermissionStep.Camera))
        val usb = requests.startUsb(initial)!!
        assertNull(requests.cancelUsbForRetry(camera.copy(id = 9000)))
        assertEquals(usb, requests.pending)
    }

    @Test
    fun `completed one shot request ignores replayed callbacks`() {
        val requests = UsbPermissionRequests()
        val initial = request()
        requests.begin(initial)
        assertTrue(requests.finish(initial))
        assertNull(requests.usbResult(initial.token, camera))
        assertFalse(requests.finish(initial))
    }
}
