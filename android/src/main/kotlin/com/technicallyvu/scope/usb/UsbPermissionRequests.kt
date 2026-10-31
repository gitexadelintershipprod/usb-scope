package com.technicallyvu.scope.usb

/** Android's USB-video permission gate applies to the device and every interface, including alternates. */
fun requiresCameraPermission(deviceClass: Int, interfaceClasses: Iterable<Int>): Boolean =
    deviceClass == 0x0e || interfaceClasses.any { it == 0x0e }

/** Device paths can be reused after unplugging, so results also carry a per-request token. */
data class UsbPermissionDevice(val name: String, val id: Int, val vendorId: Int, val productId: Int)

enum class UsbPermissionStep { Camera, Settings, Usb }

data class UsbPermissionRequest(val device: UsbPermissionDevice, val token: String, val step: UsbPermissionStep)

/**
 * Keeps a permission result tied to the device/request that caused it. All calls are on Main.
 * A detached camera dialog still owns the launcher until its callback arrives: a new camera must
 * not receive the old dialog's answer, even if Android reuses the same USB path and device id.
 */
class UsbPermissionRequests(initial: UsbPermissionRequest? = null, dialogToken: String? = null) {
    var pending: UsbPermissionRequest? = initial
        private set
    var externalActivityToken: String? = dialogToken
        private set

    fun begin(request: UsbPermissionRequest): Boolean {
        if (pending != null || externalActivityToken != null) return false
        pending = request
        if (request.step != UsbPermissionStep.Usb) externalActivityToken = request.token
        return true
    }

    fun externalResult(step: UsbPermissionStep): UsbPermissionRequest? {
        val token = externalActivityToken ?: return null
        if (pending != null && pending?.step != step) return null
        externalActivityToken = null
        return pending?.takeIf { it.token == token && it.step == step }
    }

    fun startUsb(request: UsbPermissionRequest): UsbPermissionRequest? {
        if (pending != request) return null
        val usb = request.copy(step = UsbPermissionStep.Usb)
        pending = usb
        return usb
    }

    fun usbResult(token: String?, device: UsbPermissionDevice?): UsbPermissionRequest? =
        pending?.takeIf { it.step == UsbPermissionStep.Usb && it.token == token && it.device == device }

    /** Explicit retry recovers a denied USB reply lost while the activity had no receiver. */
    fun cancelUsbForRetry(device: UsbPermissionDevice): UsbPermissionRequest? =
        pending?.takeIf { it.step == UsbPermissionStep.Usb && it.device == device }?.also { pending = null }

    fun finish(request: UsbPermissionRequest): Boolean {
        if (pending != request) return false
        pending = null
        return true
    }

    fun detach(name: String): UsbPermissionRequest? = pending?.takeIf { it.device.name == name }?.also {
        pending = null
    }
}
