package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.jpegBytes
import com.technicallyvu.scope.core.usb.IsochronousStream
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
    private class IsoTransport(
        val inner: ReplayTransport,
        private val packets: List<ByteArray> = emptyList(),
    ) : UsbTransport by inner {
        override val supportsIsochronous = true
        var requestedEndpoint = 0
        var requestedPacketSize = 0
        var closedIso = false
        private var cursor = 0
        override fun openIsochronous(endpoint: Int, packetSize: Int): IsochronousStream {
            requestedEndpoint = endpoint
            requestedPacketSize = packetSize
            inner.calls += "iso open"
            return object : IsochronousStream {
                override fun readPacket(buffer: ByteArray, timeoutMs: Int): Int {
                    if (cursor >= packets.size) { Thread.sleep(1); return 0 }
                    val bytes = packets[cursor++]
                    check(bytes.size <= buffer.size)
                    bytes.copyInto(buffer)
                    return bytes.size
                }
                override fun close() { closedIso = true; inner.calls += "iso close" }
            }
        }
    }

    @Test
    fun `native ISO uses high-bandwidth packet capacity and closes before releasing interfaces`() {
        val jpeg = TestPackets.fakeJpeg(100)
        val packet = byteArrayOf(2, 2) + jpeg
        val base = transport(bulk = false, maxPayload = 3072).apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(bulk = false, isoMaxPacketSize = 0x1400)
        }
        val t = IsoTransport(base, listOf(packet))
        val source = driver().open(t)
        try {
            val frame = runBlocking { withTimeout(3_000) { source.frames.first() } }
            assertArrayEquals(jpeg, frame.jpegBytes())
            assertEquals(0x81, t.requestedEndpoint)
            assertEquals(3072, t.requestedPacketSize)
            assertTrue(base.calls.indexOf("alt 1 0") < base.calls.indexOf("ctrl 21 01 0100 0001 34"))
            assertTrue(base.calls.indexOf("alt 1 1") < base.calls.indexOf("iso open"))
        } finally { source.close() }
        assertTrue(t.closedIso)
        assertEquals(listOf("iso close", "alt 1 0", "release 1", "release 0", "close"), base.calls.takeLast(5))
    }

    @Test
    fun `ISO selects the smallest alternate that carries the committed payload`() {
        val base = transport(bulk = false, maxPayload = 1400).apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(
                bulk = false, isoMaxPacketSize = 1024,
                isoAdditionalAlternates = listOf(2 to 0x0c00, 3 to 0x1400),
            )
        }
        val setup = driver().handshake(IsoTransport(base))
        assertTrue(setup.isIsochronous)
        assertEquals(2, setup.altSetting)
        assertEquals(2048, setup.packetSize)
    }

    @Test
    fun `ISO refuses payload requirements greater than every advertised alternate`() {
        val base = transport(bulk = false, maxPayload = 4096).apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(bulk = false, isoMaxPacketSize = 0x1400)
        }
        val error = assertThrows(UvcUnsupportedException::class.java) { driver().open(IsoTransport(base)) }
        assertTrue(error.message!!.contains("4096"))
        assertTrue(slept.isEmpty())
        assertEquals(listOf("release 1", "release 0"), base.calls.filter { it.startsWith("release") })
    }

    @Test
    fun `ISO chooses an advertised VGA preview instead of a default HD frame`() {
        val base = transport(bulk = false, maxPayload = 3072).apply {
            controlResponses[0x80 to 0x06] = UvcTestDescriptors.build(
                bulk = false, isoMaxPacketSize = 0x1400, mjpegWidth = 1920, mjpegHeight = 1080,
                additionalMjpegFrames = listOf(640 to 480),
            )
            controlResponses[0xA1 to 0x81] = UvcProbe.encode(UvcProbeControl(1, 2, 333_333, 614_400, 3072), 34)
        }
        val setup = driver().handshake(IsoTransport(base))
        assertEquals(640, setup.width)
        assertEquals(480, setup.height)
    }

    @Test
    fun `YUY2 ISO chooses a slower advertised discrete or continuous interval when bandwidth requires it`() {
        val endpoint = UvcEndpoint(0x81, false, 0x1400)
        for (frame in listOf(
            UvcFrame(1, 1024, 768, 333_333, 1_572_864, listOf(333_333, 666_666)),
            UvcFrame(1, 1024, 768, 333_333, 1_572_864, minInterval100ns = 333_333,
                maxInterval100ns = 999_999, intervalStep100ns = 333_333),
        )) {
            val selected = driver().selectIsoFrame(UvcFormat(1, UvcFormatKind.YUY2, 1, listOf(frame)), listOf(endpoint))
            assertEquals(frame, selected.first)
            assertEquals(666_666, selected.second)
        }
    }

    @Test
    fun `ISO stalls produce an actionable error instead of waiting forever`() {
        val t = IsoTransport(transport(bulk = false, maxPayload = 1024))
        val source = UvcBulkDriver(ioDispatcher = Dispatchers.Default, streamTimeoutMs = 20).open(t)
        try {
            val error = assertThrows(UsbException::class.java) {
                runBlocking { withTimeout(3_000) { source.frames.toList() } }
            }
            assertTrue(error.message!!.contains("No successful isochronous USB packets"))
        } finally { source.close() }
        assertTrue(t.closedIso)
    }

}
