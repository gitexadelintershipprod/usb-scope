package com.technicallyvu.scope.core.usb

import java.util.Locale

open class UsbException(message: String, val code: Int = 0, cause: Throwable? = null) : Exception(message, cause)

/** One failed/lost isochronous packet; the current video frame must not be emitted as intact. */
const val ISO_PACKET_ERROR = -1

/**
 * A queued isochronous IN stream. A positive [readPacket] result is exactly one completed USB
 * service-interval packet (including its UVC header), never several concatenated packets. Native
 * implementations may batch URBs internally but must retain each packet's length and status.
 * 0 means timeout or an empty packet; [ISO_PACKET_ERROR] marks a damaged/lost packet. Transport
 * failures throw [UsbException]. Reads must return within [timeoutMs]; close is idempotent and
 * cancels and releases every outstanding native request before returning.
 */
interface IsochronousStream : AutoCloseable {
    fun readPacket(buffer: ByteArray, timeoutMs: Int): Int
    override fun close()
}

/** One interface of the active configuration (alternate setting 0). */
data class UsbInterfaceInfo(val number: Int, val usbClass: Int, val subclass: Int, val protocol: Int)

data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val usbClass: Int,
    /** Interfaces of the active configuration, or empty when the platform could not read them. */
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
) {
    val idString: String get() = "%04X:%04X".format(Locale.ROOT, vendorId, productId)
}

/** The USB primitives a driver needs. One implementation per platform. */
interface UsbTransport : AutoCloseable {
    val supportsIsochronous: Boolean get() = false

    /** Called after selecting the streaming alternate setting; [packetSize] is its effective capacity. */
    fun openIsochronous(endpoint: Int, packetSize: Int): IsochronousStream =
        throw UsbException("Isochronous USB streaming is unavailable on this platform")

    fun claimInterface(iface: Int)
    fun releaseInterface(iface: Int)
    /**
     * Selects an alternate setting. Android: `UsbDeviceConnection.setInterface(usbInterface)` with the
     * [android.hardware.usb.UsbInterface] whose `getAlternateSetting()` equals [alt].
     */
    fun setAltSetting(iface: Int, alt: Int)
    /**
     * Clears a halted endpoint. Android has no direct call: issue a CLEAR_FEATURE control transfer
     * (requestType 0x02, request 0x01, value 0x00 = ENDPOINT_HALT, index = [endpoint]).
     */
    fun clearHalt(endpoint: Int)
    /** Returns bytes written. Throws [UsbException] on error. */
    fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /**
     * Returns bytes read; 0 on timeout. Throws [UsbException] on any other error, including device removal.
     *
     * Android note for the Phase 2 transport: `UsbDeviceConnection.bulkTransfer` returns -1 for BOTH a
     * timeout and a real error, so it cannot distinguish "nothing yet" from "device gone". Pair a short
     * timeout (see each driver's READ_TIMEOUT_MS) with a separate liveness check — e.g. a device-list
     * lookup or an ACTION_USB_DEVICE_DETACHED receiver — and only then map -1 to a [UsbException].
     */
    fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    /**
     * Control transfer on endpoint 0. For device-to-host requests (bit 7 of [requestType] set) [data]
     * is filled and the number of bytes received is returned; for host-to-device requests [data] is
     * sent and the number of bytes sent is returned. Throws [UsbException] on error (including STALL).
     */
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int
    /**
     * The full configuration descriptor of the active configuration: the configuration record itself
     * plus every interface, endpoint and class-specific block that follows it.
     *
     * Default implementation: two standard `GET_DESCRIPTOR` control requests (bmRequestType 0x80,
     * bRequest 0x06, wValue 0x0200 = CONFIGURATION index 0). The first reads the 9-byte header only,
     * because `wTotalLength` is what says how big the real thing is; the second reads that many bytes.
     * The result is truncated to what the device actually returned, so a short second read yields a
     * short blob rather than a tail of zeros — [com.technicallyvu.scope.core.uvc.UvcDescriptors]
     * parses defensively for exactly that reason.
     *
     * Platforms that already have the raw descriptors cached (Android) should override this and skip
     * the control traffic.
     */
    fun readConfigDescriptor(): ByteArray {
        val head = ByteArray(9)
        val n = controlTransfer(0x80, 0x06, 0x0200, 0, head, 1000)
        if (n < 4) throw UsbException("config descriptor header short ($n bytes)")
        val total = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
        if (total < 9) throw UsbException("config descriptor wTotalLength $total is not a descriptor")
        val full = ByteArray(total)
        val m = controlTransfer(0x80, 0x06, 0x0200, 0, full, 1000)
        return full.copyOf(m.coerceIn(0, total))
    }

    /**
     * USB port reset. **Not used by any driver**: on libusb a reset invalidates the device handle, so it
     * cannot be used inside an open-retry loop, and Android's `UsbDeviceConnection` has no equivalent.
     * Kept for the desktop probe and manual debugging only; a platform without it may throw.
     */
    fun resetDevice()
}
