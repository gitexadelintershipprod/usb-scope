package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.IsochronousStream
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport

/**
 * [UsbTransport] over the Android USB Host API. Android reports both timeout and error as -1 from
 * bulkTransfer, so "device gone" is derived from the [detached] flag (set from ACTION_USB_DEVICE_DETACHED)
 * plus a limit on consecutive empty reads.
 */
class AndroidUsbTransport(
    private val conn: UsbConnection,
    private val maxConsecutiveTimeouts: Int = 20,
    private val isoOpener: ((Int, Int, Int) -> IsochronousStream)? = null,
) : UsbTransport {
    @Volatile var detached: Boolean = false
    private var consecutiveTimeouts = 0
    @Volatile private var closed = false
    private var isoStream: IsochronousStream? = null
    private var bulkTransfersUsed = false

    override val supportsIsochronous: Boolean
        get() = !closed && conn.fileDescriptor >= 0 && (isoOpener != null || NativeIsochronous.available)

    @Synchronized
    override fun openIsochronous(endpoint: Int, packetSize: Int): IsochronousStream {
        if (closed || detached) throw UsbException("USB camera is closed or detached")
        if (!supportsIsochronous) throw UsbException("Native isochronous USB transport is unavailable")
        if (isoStream != null) throw UsbException("An isochronous USB stream is already active")
        if (bulkTransfersUsed) throw UsbException("Isochronous streaming requires a fresh USB connection")
        // UVC uses one IN endpoint. It must be the exclusive asynchronous reaper for this fd;
        // neither bulk UsbRequests nor another native stream may share its completion queue.
        val opened = isoOpener?.invoke(conn.fileDescriptor, endpoint, packetSize)
            ?: AndroidIsochronousStream(conn.fileDescriptor, endpoint, packetSize, detached = { detached })
        isoStream = opened
        return opened
    }

    override fun claimInterface(iface: Int) {
        if (!conn.claimInterface(iface)) throw UsbException("claimInterface $iface failed")
    }

    override fun releaseInterface(iface: Int) {
        conn.releaseInterface(iface)
    }

    override fun setAltSetting(iface: Int, alt: Int) {
        if (!conn.setInterface(iface, alt)) throw UsbException("setAltSetting $iface/$alt failed")
    }

    override fun clearHalt(endpoint: Int) {
        // Standard request: bmRequestType 0x02 (host-to-device, standard, endpoint), CLEAR_FEATURE (1), ENDPOINT_HALT (0), wIndex = endpoint
        val r = conn.controlTransfer(0x02, 0x01, 0x00, endpoint, null, 0, 1000)
        if (r < 0) throw UsbException("clearHalt %02X failed".format(endpoint))
    }

    /**
     * An unknown endpoint address is a wiring bug, not a timeout: the connection throws
     * [IllegalArgumentException] for it, and it becomes a [UsbException] so the session reports it
     * instead of silently counting it as "no data yet".
     */
    @Synchronized
    private fun transfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int = try {
        if (closed || isoStream != null) throw UsbException("Bulk transfers cannot share an active isochronous connection")
        bulkTransfersUsed = true
        conn.bulkTransfer(endpoint, buffer, length, timeoutMs)
    } catch (e: IllegalArgumentException) {
        throw UsbException("bulk transfer %02X rejected".format(endpoint) + ": " + e.message, cause = e)
    }

    override fun bulkWrite(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = transfer(endpoint, data, data.size, timeoutMs)
        if (n < 0) throw UsbException("bulkWrite %02X failed".format(endpoint))
        return n
    }

    override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (detached) throw UsbException("device detached")
        val n = transfer(endpoint, buffer, buffer.size, timeoutMs)
        if (n > 0) {
            consecutiveTimeouts = 0
            return n
        }
        // A zero-byte completion is indistinguishable from a timeout with pipelined UsbRequests
        // (an errored URB also completes with 0 bytes), so it must count toward the liveness limit
        // the same as -1 or a persistent endpoint fault would spin forever.
        if (detached) throw UsbException("device detached")
        consecutiveTimeouts++
        if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
            throw UsbException("no data after $maxConsecutiveTimeouts reads; assuming the device is gone")
        }
        return 0
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val r = conn.controlTransfer(requestType, request, value, index, data, data.size, timeoutMs)
        if (r < 0) throw UsbException("controlTransfer %02X/%02X failed".format(requestType, request))
        return r
    }

    /**
     * Android has already read the descriptors when the device was enumerated, so the configuration
     * is sliced out of [UsbConnection.rawDescriptors] instead of costing two control transfers: the
     * raw blob is the 18-byte device descriptor followed by the configuration descriptor(s), so the
     * first record of type 2 (CONFIGURATION) starts the part we want and its `wTotalLength` says how
     * far it runs. Anything unexpected (no raw bytes, no configuration record, a length that runs off
     * the end) falls back to the portable GET_DESCRIPTOR path.
     */
    override fun readConfigDescriptor(): ByteArray {
        val raw = conn.rawDescriptors() ?: return super.readConfigDescriptor()
        var i = 0
        while (i + 2 <= raw.size) {
            val length = raw[i].toInt() and 0xFF
            if (length < 2) break
            if ((raw[i + 1].toInt() and 0xFF) == DT_CONFIGURATION) {
                if (i + 4 > raw.size) break
                val total = (raw[i + 2].toInt() and 0xFF) or ((raw[i + 3].toInt() and 0xFF) shl 8)
                if (total < length || i + total > raw.size) break
                return raw.copyOfRange(i, i + total)
            }
            i += length
        }
        return super.readConfigDescriptor()
    }

    override fun resetDevice() {
        throw UsbException("resetDevice is not available on Android")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { isoStream?.close() }
        finally {
            isoStream = null
            conn.close()
        }
    }

    private companion object {
        /** Standard descriptor type CONFIGURATION. */
        const val DT_CONFIGURATION = 2
    }
}
