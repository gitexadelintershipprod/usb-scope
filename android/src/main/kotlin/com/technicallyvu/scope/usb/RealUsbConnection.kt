package com.technicallyvu.scope.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [UsbConnection] over the Android USB Host API.
 *
 * Bulk IN reads are **pipelined**, not synchronous. [UsbDeviceConnection.bulkTransfer] submits one
 * URB per call and returns when it completes, so between one transfer completing and the next being
 * submitted (a JNI round trip plus our per-read coroutine dispatch) nothing at all is queued on the
 * endpoint. The single-interface YUV endoscope has a small FIFO and simply drops rows while the host is not
 * asking: measured on a Galaxy Z Fold 7 (Android 16), *every* frame arrived truncated
 * (`framesPartial` climbing ~11/s at 10.9 fps), while the desktop libusb backend — same driver, same
 * 16 KB reads, but asynchronous transfers — lost about one frame in six. The vendor app uses queued
 * asynchronous transfers for the same reason.
 *
 * So each bulk IN endpoint gets a lazily created pool of [QUEUE_DEPTH] [UsbRequest]s, each with its
 * own direct [ByteBuffer] of exactly the length the caller asks for. A read waits for the next
 * completion via [UsbDeviceConnection.requestWait], copies the bytes out and immediately re-queues
 * that request, so the endpoint always has requests outstanding and the device never stalls on us.
 *
 * OUT endpoints keep the plain synchronous path: they only carry the MJPEG driver's short
 * commands, where latency does not matter and queueing would only add complexity.
 *
 * Callers must not close() concurrently with a bulkTransfer; the core frame sources guarantee this
 * by holding their ioLock across both.
 */
class RealUsbConnection(private val device: UsbDevice, private val connection: UsbDeviceConnection) : UsbConnection {
    /**
     * Alternate setting currently selected per interface id (default 0). [UsbDevice] lists every
     * alternate setting as its own [UsbInterface], and different alternate settings may reuse the
     * same endpoint address, so an endpoint address alone does not identify an endpoint. Written on
     * the session thread, read on the IO thread, hence the concurrent map.
     */
    private val selectedAlt = ConcurrentHashMap<Int, Int>()

    override val fileDescriptor: Int get() = if (closed) -1 else connection.fileDescriptor

    /**
     * Guards [inPools], [owner] and every [UsbRequest] they hold. In practice all reads for one
     * endpoint come from the single driver loop thread, but close/release run on the session thread,
     * so the pools are locked anyway. Never held across [UsbDeviceConnection.requestWait] so that a
     * close is not stuck behind a read's full timeout.
     */
    private val poolLock = ReentrantLock()

    /** Pool of in-flight requests per bulk IN endpoint address. Guarded by [poolLock]. */
    private val inPools = HashMap<Int, InPool>()

    /**
     * Which pool owns a given request. [UsbDeviceConnection.requestWait] is per *connection*, not
     * per endpoint, so a read on one endpoint can be handed another endpoint's completed request;
     * this map is how we recognise it and hand it back to its own pool. Guarded by [poolLock].
     */
    private val owner = IdentityHashMap<UsbRequest, InPool>()

    @Volatile private var closed = false

    private inner class InPool(val endpoint: UsbEndpoint, val length: Int) {
        /** Request -> its direct buffer. Iteration order is irrelevant; identity is what matters. */
        private val buffers = IdentityHashMap<UsbRequest, ByteBuffer>()

        /** Creates and queues the whole pool. Throws (after cleaning up) if the device refuses. */
        fun start() {
            try {
                repeat(QUEUE_DEPTH) {
                    val req = UsbRequest()
                    if (!req.initialize(connection, endpoint)) {
                        req.close()
                        throw IllegalArgumentException("UsbRequest.initialize failed for %02X".format(endpoint.address))
                    }
                    val buf = ByteBuffer.allocateDirect(length)
                    buffers[req] = buf
                    owner[req] = this
                    if (!req.queue(buf)) throw IllegalArgumentException("queue failed for %02X".format(endpoint.address))
                }
            } catch (e: Throwable) {
                shutdown()
                throw e
            }
        }

        /**
         * Copies a completed request's payload into [out] and puts the request straight back on the
         * endpoint. Returns the number of bytes copied into [out] (the buffer's position, clamped to
         * [wanted]).
         */
        fun consume(req: UsbRequest, out: ByteArray, wanted: Int): Int {
            val buf = buffers.getValue(req)
            val received = buf.position()
            if (received > 0) {
                buf.rewind()
                buf.get(out, 0, minOf(received, wanted))
            }
            buf.clear()
            if (!req.queue(buf)) {
                Log.w(TAG, "requeue failed on endpoint %02X; pool depth now %d".format(endpoint.address, buffers.size - 1))
                throw IllegalArgumentException("requeue failed")
            }
            return minOf(received, wanted)
        }

        /** Puts a request belonging to this pool back on its endpoint without reading it. */
        fun requeue(req: UsbRequest) {
            val buf = buffers[req] ?: return
            buf.clear()
            if (!req.queue(buf)) {
                Log.w(TAG, "requeue failed on endpoint %02X; pool depth now %d".format(endpoint.address, buffers.size - 1))
                throw IllegalArgumentException("requeue failed")
            }
        }

        /**
         * Cancels every request before freeing any of them, so no URB is still in flight while its
         * neighbours' native memory is released. Safe to call twice.
         */
        fun shutdown() {
            for (req in buffers.keys) runCatching { req.cancel() }
            for (req in buffers.keys) {
                runCatching { req.close() }
                owner.remove(req)
            }
            buffers.clear()
        }
    }

    private fun iface(number: Int, alt: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.id == number && it.alternateSetting == alt) return it
        }
        return null
    }

    /**
     * Resolves [address] against the alternate settings actually selected, falling back to any
     * interface that carries the address (e.g. before the interface has been claimed).
     * Throws [IllegalArgumentException] when no interface has that endpoint at all, so a wiring
     * mistake surfaces as an error rather than looking like a transfer timeout.
     */
    private fun endpointFor(address: Int): UsbEndpoint {
        var fallback: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address != address) continue
                if (iface.alternateSetting == (selectedAlt[iface.id] ?: 0)) return ep
                if (fallback == null) fallback = ep
            }
        }
        return fallback ?: throw IllegalArgumentException("no endpoint %02X".format(address))
    }

    /** Drains the request pools of every endpoint carried by interface [number], in any alt setting. */
    private fun dropPoolsOf(number: Int) = poolLock.withLock {
        val addresses = HashSet<Int>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id != number) continue
            for (e in 0 until iface.endpointCount) addresses += iface.getEndpoint(e).address
        }
        for (address in addresses) inPools.remove(address)?.shutdown()
    }

    override fun claimInterface(number: Int): Boolean =
        iface(number, 0)?.let { connection.claimInterface(it, true).also { ok -> if (ok) selectedAlt[number] = 0 } } ?: false

    override fun releaseInterface(number: Int): Boolean {
        // Requests must not outlive the interface they were queued against.
        dropPoolsOf(number)
        return iface(number, 0)?.let { connection.releaseInterface(it).also { ok -> if (ok) selectedAlt.remove(number) } } ?: false
    }

    override fun setInterface(number: Int, alt: Int): Boolean {
        // A different alt setting is a different set of endpoints; requests bound to the old ones go.
        dropPoolsOf(number)
        return iface(number, alt)?.let { connection.setInterface(it).also { ok -> if (ok) selectedAlt[number] = alt } } ?: false
    }

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val ep = endpointFor(endpoint)
        val pipelined = ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        return if (pipelined) pipelinedRead(ep, buffer, length, timeoutMs)
        else connection.bulkTransfer(ep, buffer, length, timeoutMs)
    }

    /**
     * Waits for the next completion on [ep], keeping [QUEUE_DEPTH] requests outstanding at all times.
     * Returns the byte count, or -1 on timeout (Android's convention, which [AndroidUsbTransport]
     * turns into "no data yet" plus a consecutive-timeout limit).
     */
    private fun pipelinedRead(ep: UsbEndpoint, out: ByteArray, length: Int, timeoutMs: Int): Int {
        val pool = poolLock.withLock {
            if (closed) return -1
            val existing = inPools[ep.address]
            // Callers always ask for the same size, but a change would silently truncate: rebuild.
            if (existing != null && existing.length == length) existing
            else {
                existing?.shutdown()
                InPool(ep, length).also { it.start(); inPools[ep.address] = it }
            }
        }
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            // requestWait(0) would block forever; out of budget means timed out.
            if (remainingMs <= 0) return -1
            val req = try {
                connection.requestWait(remainingMs)
            } catch (e: TimeoutException) {
                null
            } ?: return -1
            val n = poolLock.withLock {
                if (closed) return -1
                val holder = owner[req]
                if (holder === pool) {
                    // Ours: copy it out and immediately put the request back on the wire.
                    pool.consume(req, out, length)
                } else {
                    // Another endpoint's request: keep its pipeline full and keep waiting for ours.
                    // An unknown request belongs to a pool we already tore down; just drop it.
                    holder?.requeue(req)
                    NOT_OURS
                }
            }
            if (n != NOT_OURS) return n
        }
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int =
        connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)

    override fun rawDescriptors(): ByteArray? = connection.rawDescriptors

    override fun close() {
        poolLock.withLock {
            closed = true
            for (pool in inPools.values) pool.shutdown()
            inPools.clear()
        }
        connection.close()
    }

    private companion object {
        private const val TAG = "RealUsbConnection"

        /**
         * Requests kept outstanding per IN endpoint. Eight 16 KB reads is ~128 KB in flight, far more
         * than the ~150 KB/frame stream needs to ride out a scheduling hiccup, at negligible cost.
         */
        const val QUEUE_DEPTH = 8

        /** Sentinel: the completion we got belongs to some other endpoint, so keep waiting. */
        const val NOT_OURS = -2
    }
}
