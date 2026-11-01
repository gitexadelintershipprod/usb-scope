package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.UsbException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AndroidUsbTransportTest {
    private class FakeConnection : UsbConnection {
        val calls = mutableListOf<String>()
        override var fileDescriptor: Int = -1
        var bulkResults = ArrayDeque<Int>()
        var controlReply: ByteArray? = null
        var raw: ByteArray? = null
        override fun rawDescriptors(): ByteArray? = raw.also { calls += "raw" }
        override fun claimInterface(number: Int) = true.also { calls += "claim $number" }
        override fun releaseInterface(number: Int) = true.also { calls += "release $number" }
        override fun setInterface(number: Int, alt: Int) = true.also { calls += "alt $number $alt" }
        override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            calls += "bulk %02X %d".format(endpoint, length)
            val r = bulkResults.removeFirstOrNull() ?: -1
            if (r > 0) for (i in 0 until r) buffer[i] = 7
            return r
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int {
            calls += "ctrl %02X %02X %04X %04X %d".format(requestType, request, value, index, length)
            val reply = controlReply ?: return length
            reply.copyInto(buffer!!, 0, 0, minOf(reply.size, length)); return minOf(reply.size, length)
        }
        override fun close() { calls += "close" }
    }

    @Test
    fun `iso stream has exclusive completion queue and closes before Android fd`() {
        val c = FakeConnection().apply { fileDescriptor = 7 }
        var closes = 0
        val stream = object : com.technicallyvu.scope.core.usb.IsochronousStream {
            override fun readPacket(buffer: ByteArray, timeoutMs: Int) = 0
            override fun close() { if (closes++ == 0) c.calls += "iso close" }
        }
        val t = AndroidUsbTransport(c, isoOpener = { fd, endpoint, packetSize ->
            assertEquals(7, fd); assertEquals(0x81, endpoint); assertEquals(3072, packetSize)
            stream
        })
        org.junit.jupiter.api.Assertions.assertTrue(t.supportsIsochronous)
        assertEquals(stream, t.openIsochronous(0x81, 3072))
        assertThrows(UsbException::class.java) { t.bulkRead(0x81, ByteArray(16), 500) }
        assertThrows(UsbException::class.java) { t.openIsochronous(0x81, 3072) }
        t.close(); t.close()
        assertEquals(listOf("iso close", "close"), c.calls)
    }

    @Test
    fun `a connection previously used for bulk cannot hand its completion queue to iso`() {
        val c = FakeConnection().apply { fileDescriptor = 7; bulkResults += 4 }
        val t = AndroidUsbTransport(c, isoOpener = { _, _, _ -> error("must not open native stream") })
        t.bulkRead(0x82, ByteArray(16), 500)
        assertThrows(UsbException::class.java) { t.openIsochronous(0x81, 3072) }
        t.close()
    }

    @Test
    fun `timeouts return zero until the limit then throw`() {
        val c = FakeConnection()
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, buf, 500) }
    }

    @Test
    fun `a run of empty completions trips the limit like timeouts`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(0, 0, 0)) }
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, buf, 500) }
    }

    @Test
    fun `data resets the timeout counter`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(-1, -1, 4, -1, -1)) }
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        t.bulkRead(0x82, buf, 500); t.bulkRead(0x82, buf, 500)
        assertEquals(4, t.bulkRead(0x82, buf, 500)); assertEquals(7, buf[3].toInt())
        assertEquals(0, t.bulkRead(0x82, buf, 500)); assertEquals(0, t.bulkRead(0x82, buf, 500))
    }

    @Test
    fun `detach makes the next read throw`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(4)) }
        val t = AndroidUsbTransport(c)
        t.detached = true
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, ByteArray(16), 500) }
    }

    @Test
    fun `control transfer fills IN buffers and reports lengths`() {
        val c = FakeConnection().apply { controlReply = byteArrayOf(1, 2, 3) }
        val t = AndroidUsbTransport(c)
        val buf = ByteArray(512)
        assertEquals(3, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        c.controlReply = null
        assertEquals(64, t.controlTransfer(0x20, 1, 5, 0, ByteArray(64), 1000))
        assertEquals(listOf("ctrl A0 00 0005 0000 512", "ctrl 20 01 0005 0000 64"), c.calls)
    }

    @Test
    fun `clearHalt is a CLEAR_FEATURE ENDPOINT_HALT control request`() {
        val c = FakeConnection()
        AndroidUsbTransport(c).clearHalt(0x01)
        assertEquals(listOf("ctrl 02 01 0000 0001 0"), c.calls)
    }

    @Test
    fun `an unknown endpoint is a UsbException, not a timeout`() {
        val c = object : UsbConnection by FakeConnection() {
            override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int =
                throw IllegalArgumentException("no endpoint 83")
        }
        val t = AndroidUsbTransport(c)
        assertThrows(UsbException::class.java) { t.bulkRead(0x83, ByteArray(16), 500) }
        assertThrows(UsbException::class.java) { t.bulkWrite(0x03, ByteArray(4), 500) }
    }

    @Test
    fun `readConfigDescriptor slices the configuration out of the cached raw descriptors`() {
        // Android hands back the device descriptor (18 bytes, type 1) followed by the configuration.
        val config = byteArrayOf(0x09, 0x02, 0x0C, 0x00, 0x01, 0x01, 0x00, 0x80.toByte(), 0x32) +
            byteArrayOf(0x03, 0x0B, 0x00)
        val device = ByteArray(18).also { it[0] = 18; it[1] = 1 }
        val c = FakeConnection().apply { raw = device + config }

        assertArrayEquals(config, AndroidUsbTransport(c).readConfigDescriptor())
        // No control traffic: the descriptors were already cached by the platform.
        assertEquals(listOf("raw"), c.calls)
    }

    @Test
    fun `readConfigDescriptor falls back to control transfers when no raw descriptors are available`() {
        val config = byteArrayOf(0x09, 0x02, 0x09, 0x00, 0x01, 0x01, 0x00, 0x80.toByte(), 0x32)
        val c = FakeConnection().apply { raw = null; controlReply = config }

        assertArrayEquals(config, AndroidUsbTransport(c).readConfigDescriptor())
        assertEquals(
            listOf("raw", "ctrl 80 06 0200 0000 9", "ctrl 80 06 0200 0000 9"),
            c.calls,
        )
    }

    @Test
    fun `claim failure and reset are UsbExceptions`() {
        val c = object : UsbConnection by FakeConnection() { override fun claimInterface(number: Int) = false }
        val t = AndroidUsbTransport(c)
        assertThrows(UsbException::class.java) { t.claimInterface(0) }
        assertThrows(UsbException::class.java) { t.resetDevice() }
    }
}
