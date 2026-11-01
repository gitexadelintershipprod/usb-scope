package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.ISO_PACKET_ERROR
import com.technicallyvu.scope.core.usb.IsochronousStream
import com.technicallyvu.scope.core.usb.UsbException

/** Injectable JNI boundary: packet metadata must survive batching without concatenating headers. */
internal interface IsochronousNativeCalls {
    fun open(fd: Int, endpoint: Int, packetSize: Int): Long
    fun read(handle: Long, payload: ByteArray, metadata: IntArray, timeoutMs: Int): Int
    fun close(handle: Long)
}

private object JniIsochronousCalls : IsochronousNativeCalls {
    override fun open(fd: Int, endpoint: Int, packetSize: Int) = NativeIsochronous.open(fd, endpoint, packetSize)
    override fun read(handle: Long, payload: ByteArray, metadata: IntArray, timeoutMs: Int) =
        NativeIsochronous.read(handle, payload, metadata, timeoutMs)
    override fun close(handle: Long) = NativeIsochronous.close(handle)
}

/** Dequeues a native URB batch once, then exposes each service-interval packet independently. */
internal class AndroidIsochronousStream(
    fd: Int,
    endpoint: Int,
    private val packetSize: Int,
    private val detached: () -> Boolean = { false },
    private val calls: IsochronousNativeCalls = JniIsochronousCalls,
) : IsochronousStream {
    private val payload: ByteArray
    private val metadata = IntArray(PACKETS_PER_BATCH * 2)
    private var handle: Long
    private var packetCount = 0
    private var packetIndex = 0

    init {
        require(fd >= 0 && endpoint in 0x81..0x8f && packetSize in 1..MAX_PACKET_SIZE)
        payload = ByteArray(packetSize * PACKETS_PER_BATCH)
        handle = try {
            calls.open(fd, endpoint, packetSize).also { if (it == 0L) throw UsbException("Native USB stream returned no handle") }
        } catch (e: Exception) {
            throw UsbException("Could not start isochronous USB: ${e.message}", cause = e)
        }
    }

    @Synchronized
    override fun readPacket(buffer: ByteArray, timeoutMs: Int): Int {
        if (handle == 0L) throw UsbException("Isochronous USB stream is closed")
        if (detached()) throw UsbException("USB camera detached")
        if (buffer.size < packetSize || timeoutMs <= 0) throw UsbException("Invalid isochronous read buffer or timeout")
        if (packetIndex >= packetCount) {
            packetCount = try {
                calls.read(handle, payload, metadata, timeoutMs.coerceAtMost(500))
            } catch (e: Exception) {
                throw UsbException("Isochronous USB read failed: ${e.message}", cause = e)
            }
            packetIndex = 0
            if (packetCount !in 0..PACKETS_PER_BATCH) throw UsbException("Invalid native isochronous packet count")
            if (packetCount == 0) return 0
        }
        val index = packetIndex++
        val length = metadata[index * 2]
        val status = metadata[index * 2 + 1]
        if (status != 0) return ISO_PACKET_ERROR
        if (length !in 0..packetSize) throw UsbException("Invalid native isochronous packet length")
        if (length > 0) payload.copyInto(buffer, 0, index * packetSize, index * packetSize + length)
        return length
    }

    @Synchronized
    override fun close() {
        val previous = handle
        handle = 0L
        packetCount = 0
        packetIndex = 0
        if (previous != 0L) {
            try { calls.close(previous) }
            catch (e: Exception) { throw UsbException("Isochronous USB cleanup failed: ${e.message}", cause = e) }
        }
    }

    companion object {
        const val PACKETS_PER_BATCH = 32
        const val MAX_PACKET_SIZE = 32 * 1024
    }
}
