package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.jpegBytes
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UvcBulkDriverTest {
    private val slept = mutableListOf<Long>()

    private fun driver(sink: PacketSink? = null) =
        UvcBulkDriver(clock = { 42L }, sleep = { slept += it }, ioDispatcher = Dispatchers.Default, packetSink = sink)

    /** One complete MJPEG frame per payload, FID alternating, EOF on each. */
    private fun mjpegPayloads(count: Int): Pair<List<LoggedPacket>, List<ByteArray>> {
        val jpegs = (0 until count).map { TestPackets.fakeJpeg(40 + it) }
        val packets = jpegs.mapIndexed { i, jpeg ->
            val out = ByteArray(12 + jpeg.size)
            out[0] = 12
            out[1] = ((i and 1) or 0x02).toByte() // FID + EOF
            jpeg.copyInto(out, 12)
            LoggedPacket(i.toLong(), out)
        }
        return packets to jpegs
    }

    /** Records the capacity of every bulk read buffer, so a test can check the reads are payload-sized. */
    private class SizingTransport(private val inner: ReplayTransport) : UsbTransport by inner {
        val readSizes = mutableListOf<Int>()
        override fun bulkRead(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
            synchronized(readSizes) { readSizes += buffer.size }
            return inner.bulkRead(endpoint, buffer, timeoutMs)
        }
    }

    private fun transport(
        packets: List<LoggedPacket> = emptyList(),
        bulk: Boolean = true,
        maxPayload: Int = 16_384,
    ) = ReplayTransport(packets, videoEndpoint = 0x81).apply {
        controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(bulk = bulk)
        controlResponses[0xA1 to 0x81] =
            UvcProbe.encode(UvcProbeControl(1, 1, 333_333, 614_400, maxPayload), 34)
    }

    @Test
    fun `matches a VideoControl interface and nothing else`() {
        val d = driver()
        val vc = UsbInterfaceInfo(0, 0x0E, 0x01, 0)
        val vs = UsbInterfaceInfo(1, 0x0E, 0x02, 0)
        assertTrue(d.matches(UsbDeviceInfo(0x046D, 0x0825, 0xEF, listOf(vc, vs))))
        // VideoControl alone is enough: the streaming interfaces come from the configuration
        // descriptor, which this summary does not always reflect. open() reports a device that
        // genuinely has none.
        assertTrue(d.matches(UsbDeviceInfo(0x046D, 0x0825, 0xEF, listOf(vc))))
        assertFalse(d.matches(UsbDeviceInfo(0x046D, 0x0825, 0xEF, listOf(vs))))
        assertFalse(d.matches(UsbDeviceInfo(0x046D, 0x0825, 0xEF, listOf(UsbInterfaceInfo(0, 0x03, 0x01, 1)))))
        assertFalse(d.matches(UsbDeviceInfo(0x046D, 0x0825, 0xEF)))
        assertEquals("uvc-bulk", d.id)
        assertEquals(0, d.defaultRotation)
    }

    @Test
    fun `open reads the descriptors, claims both interfaces, negotiates and selects the alt setting`() {
        val t = transport()
        driver().open(t)
        assertEquals(
            listOf(
                "ctrl 80 06 0200 0000 9",
                "ctrl 80 06 0200 0000 ${UvcTestDescriptors.build().size}",
                "claim 0",
                "claim 1",
                "ctrl 21 01 0100 0001 34",
                "ctrl A1 81 0100 0001 34",
                "ctrl 21 01 0200 0001 34",
                "alt 1 0",
            ),
            t.calls,
        )
    }

    @Test
    fun `payloads on the bulk endpoint become JPEG frames`() {
        val (packets, jpegs) = mjpegPayloads(3)
        val t = transport(packets)
        val source = driver().open(t)
        val frames = runBlocking { source.frames.take(3).toList() }

        assertEquals(3, frames.size)
        frames.forEachIndexed { i, f ->
            assertTrue(f.data is FrameData.Jpeg)
            assertArrayEquals(jpegs[i], f.jpegBytes())
        }
        assertEquals(42L, frames[0].timestampNanos)
        assertEquals(3, source.stats.value.framesEmitted)
        assertTrue(source.stats.value.bytesReceived > 0)
    }

    @Test
    fun `a small negotiated payload sizes the reads so no read spans a payload boundary`() {
        // The device commits 4096, a quarter of CHUNK_SIZE. Reading 16 KiB would swallow up to four
        // payloads per read, and the parser only looks for a header once a payload has ended -- the
        // second payload's header would be parsed as picture data.
        val jpeg = TestPackets.fakeJpeg(4080)          // 4084 bytes; + a 12-byte header = 4096 exactly
        val packets = (0 until 2).map { i ->
            val out = ByteArray(4096)
            out[0] = 12
            out[1] = ((i and 1) or 0x02).toByte()      // FID + EOF
            jpeg.copyInto(out, 12)
            LoggedPacket(i.toLong(), out)
        }
        val t = SizingTransport(transport(packets, maxPayload = 4096))
        val source = driver().open(t)
        val frames = runBlocking { source.frames.take(2).toList() }

        assertEquals(2, frames.size)
        frames.forEach { assertArrayEquals(jpeg, it.jpegBytes()) }
        assertEquals(2, source.stats.value.framesEmitted)
        assertEquals(0, source.stats.value.framesDropped)
        assertEquals(0, source.stats.value.headerErrors)
        val sizes = synchronized(t.readSizes) { t.readSizes.toList() }
        assertTrue(sizes.isNotEmpty(), "no bulk read was issued")
        assertTrue(sizes.all { it == 4096 }, "reads must be one payload each, were ${sizes.distinct()}")
    }

    @Test
    fun `the packet sink sees the raw reads`() {
        val (packets, _) = mjpegPayloads(2)
        var seen = 0
        val t = transport(packets)
        runCatching { runBlocking { driver { _, _, _ -> seen++ }.open(t).frames.toList() } }
        assertTrue(seen > 0)
    }

    @Test
    fun `an isochronous-only camera is refused with a plain explanation and no retries`() {
        val t = transport(bulk = false)
        val e = assertThrows(UsbException::class.java) { driver().open(t) }
        assertTrue(e.message!!.contains("isochronous"), "message was: ${e.message}")
        assertTrue(slept.isEmpty(), "an unsupported configuration must not be retried")
    }

    @Test
    fun `a non-UVC descriptor blob is refused`() {
        val t = ReplayTransport(emptyList(), videoEndpoint = 0x81).apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.withoutVc()
        }
        assertThrows(UsbException::class.java) { driver().open(t) }
    }

    @Test
    fun `invalid or excessive dimensions are refused before claiming or starting the camera`() {
        for ((width, height) in listOf(0 to 480, 4097 to 1, 4096 to 4096, 46_341 to 46_341)) {
            val t = transport().apply {
                controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(
                    mjpegWidth = width, mjpegHeight = height,
                )
            }
            val error = assertThrows(UvcUnsupportedException::class.java) { driver().open(t) }
            assertTrue(error.message!!.contains("${width}x$height"))
            assertTrue(t.calls.none { it.startsWith("claim ") || it.startsWith("alt ") })
            assertTrue(slept.isEmpty())
        }
    }

    @Test
    fun `a UHD format is within the early decoded allocation limit`() {
        val t = transport().apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(
                mjpegWidth = 3840, mjpegHeight = 2160,
            )
        }
        val setup = driver().handshake(t)
        assertEquals(3840, setup.width)
        assertEquals(2160, setup.height)
    }

    @Test
    fun `open retries a transient failure without a device reset, releasing between attempts`() {
        val t = transport().apply { failuresBeforeSuccess = 3 }
        assertThrows(UsbException::class.java) { driver().open(t) }
        assertEquals(0, t.resets)
        assertEquals(3, t.calls.count { it == "claim 0" })
        // Whatever a failed attempt may have claimed is handed back before the next one tries.
        assertEquals(
            listOf("claim 0", "release 0", "claim 0", "release 0", "claim 0", "release 0"),
            t.calls.filter { it.startsWith("claim ") || it.startsWith("release ") },
        )
        // Two waits, not three: nothing is gained by sleeping after the last attempt.
        assertEquals(listOf(1500L, 1500L), slept)
    }

    @Test
    fun `a successful open claims without releasing`() {
        val t = transport()
        driver().open(t)
        assertTrue(t.calls.none { it.startsWith("release ") }, "calls=${t.calls}")
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `close parks the interface at alt 0 and releases streaming before control`() {
        val t = transport()
        driver().open(t).close()
        assertEquals(listOf("alt 1 0", "release 1", "release 0", "close"), t.calls.takeLast(4))
    }
}
