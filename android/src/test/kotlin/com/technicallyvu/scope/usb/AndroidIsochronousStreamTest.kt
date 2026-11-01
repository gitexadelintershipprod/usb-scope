package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.ISO_PACKET_ERROR
import com.technicallyvu.scope.core.usb.UsbException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class AndroidIsochronousStreamTest {
    private class FakeNative : IsochronousNativeCalls {
        var reads = 0
        var closes = 0
        var count = 3
        var failure: Exception? = null
        override fun open(fd: Int, endpoint: Int, packetSize: Int): Long = 19L
        override fun read(handle: Long, payload: ByteArray, metadata: IntArray, timeoutMs: Int): Int {
            reads++
            failure?.let { throw it }
            payload[0] = 2; payload[1] = 11; payload[8] = 2; payload[9] = 22
            metadata[0] = 2; metadata[1] = 0
            metadata[2] = 2; metadata[3] = 0
            metadata[4] = 0; metadata[5] = -71
            return count
        }
        override fun close(handle: Long) { closes++ }
    }

    @Test
    fun `one native batch preserves packet boundaries and failed packet status`() {
        val native = FakeNative()
        val stream = AndroidIsochronousStream(7, 0x81, 8, calls = native)
        val out = ByteArray(8)
        assertEquals(2, stream.readPacket(out, 500))
        assertArrayEquals(byteArrayOf(2, 11), out.copyOf(2))
        assertEquals(2, stream.readPacket(out, 500))
        assertArrayEquals(byteArrayOf(2, 22), out.copyOf(2))
        assertEquals(ISO_PACKET_ERROR, stream.readPacket(out, 500))
        assertEquals(1, native.reads)
        stream.close(); stream.close()
        assertEquals(1, native.closes)
        assertThrows(UsbException::class.java) { stream.readPacket(out, 500) }
    }

    @Test
    fun `detach is checked before cached packet delivery and native errors remain actionable`() {
        val native = FakeNative()
        var detached = false
        val stream = AndroidIsochronousStream(7, 0x81, 8, detached = { detached }, calls = native)
        stream.readPacket(ByteArray(8), 500)
        detached = true
        assertThrows(UsbException::class.java) { stream.readPacket(ByteArray(8), 500) }
        stream.close()
        val broken = FakeNative().apply { failure = IOException("ENOSPC: USB bandwidth") }
        val errorStream = AndroidIsochronousStream(7, 0x81, 8, calls = broken)
        val error = assertThrows(UsbException::class.java) { errorStream.readPacket(ByteArray(8), 500) }
        assertTrue(error.message!!.contains("ENOSPC"))
        errorStream.close()
    }

    @Test
    fun `timeout returns no packet and malformed native counts are rejected`() {
        val native = FakeNative().apply { count = 0 }
        val stream = AndroidIsochronousStream(7, 0x81, 8, calls = native)
        assertEquals(0, stream.readPacket(ByteArray(8), 10))
        native.count = 33
        assertThrows(UsbException::class.java) { stream.readPacket(ByteArray(8), 10) }
        stream.close()
    }
}
