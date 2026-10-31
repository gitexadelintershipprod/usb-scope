package com.technicallyvu.scope.core.uvc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UvcDescriptorsTest {

    @Test
    fun `bulk layout is parsed fully`() {
        val device = requireNotNull(UvcDescriptors.parse(UvcTestDescriptors.build(bulk = true)))

        assertEquals(0x0110, device.bcdUvc)
        assertEquals(0, device.controlInterface)
        assertEquals(1, device.streaming.size)

        val vs = device.streaming[0]
        assertEquals(1, vs.number)
        assertEquals(0x81, vs.inputEndpoint)

        assertEquals(2, vs.formats.size)
        val mjpeg = vs.formats[0]
        assertEquals(1, mjpeg.index)
        assertEquals(UvcFormatKind.MJPEG, mjpeg.kind)
        assertEquals(1, mjpeg.defaultFrameIndex)
        assertEquals(1, mjpeg.frames.size)
        val frame = mjpeg.frames[0]
        assertEquals(1, frame.index)
        assertEquals(640, frame.width)
        assertEquals(480, frame.height)
        assertEquals(333_333, frame.defaultInterval100ns)
        assertEquals(614_400, frame.maxFrameBufferSize)

        val yuy2 = vs.formats[1]
        assertEquals(UvcFormatKind.YUY2, yuy2.kind)
        assertEquals(1, yuy2.frames.size)
        assertEquals(320, yuy2.frames[0].width)
        assertEquals(240, yuy2.frames[0].height)
        assertEquals(153_600, yuy2.frames[0].maxFrameBufferSize)

        assertEquals(1, vs.altSettings.size)
        val alt0 = vs.altSettings.first { it.alt == 0 }
        assertEquals(1, alt0.endpoints.size)
        val ep = alt0.endpoints[0]
        assertEquals(0x81, ep.address)
        assertTrue(ep.isBulk)
        assertEquals(512, ep.maxPacketSize)
    }

    @Test
    fun `iso layout puts the endpoint on alt 1 and leaves alt 0 empty`() {
        val device = requireNotNull(UvcDescriptors.parse(UvcTestDescriptors.build(bulk = false)))

        val vs = device.streaming[0]
        assertEquals(2, vs.altSettings.size)

        val alt0 = vs.altSettings.first { it.alt == 0 }
        assertTrue(alt0.endpoints.isEmpty())

        val alt1 = vs.altSettings.first { it.alt == 1 }
        assertEquals(1, alt1.endpoints.size)
        val ep = alt1.endpoints[0]
        assertEquals(0x81, ep.address)
        assertFalse(ep.isBulk)
        assertEquals(1024, ep.maxPacketSize)
    }

    /**
     * The GUID bytes are typed out here rather than taken from the production constant, so that the
     * test would still fail if that constant were byte-swapped again. Wire order for
     * `{32595559-0000-0010-8000-00AA00389B71}`: `Data1` little-endian, i.e. ASCII "YUY2" first.
     */
    @Test
    fun `the wire-order YUY2 guid is recognised`() {
        val guid = byteArrayOf(
            0x59, 0x55, 0x59, 0x32, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )
        val device = requireNotNull(UvcDescriptors.parse(UvcTestDescriptors.build(uncompressedGuid = guid)))
        assertEquals(UvcFormatKind.YUY2, device.streaming[0].formats[1].kind)
    }

    @Test
    fun `a guid with Data1 in text order is not YUY2`() {
        // The pre-fix constant: the textual GUID's first group written big-endian.
        val wrong = byteArrayOf(
            0x32, 0x59, 0x55, 0x59, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )
        val device = requireNotNull(UvcDescriptors.parse(UvcTestDescriptors.build(uncompressedGuid = wrong)))
        assertEquals(UvcFormatKind.OTHER, device.streaming[0].formats[1].kind)
    }

    @Test
    fun `config without a video control interface yields null`() {
        assertNull(UvcDescriptors.parse(UvcTestDescriptors.withoutVc()))
    }

    @Test
    fun `truncated descriptor does not throw`() {
        val full = UvcTestDescriptors.build(bulk = true)
        // Cut off ten bytes from the tail, landing mid FRAME_MJPEG so its declared bLength no
        // longer fits in the remaining bytes.
        val truncated = full.copyOf(full.size - 10)

        val device = UvcDescriptors.parse(truncated)

        // Must not throw (asserted implicitly by reaching this line); the VC header was fully
        // read before the truncation point, so the device itself is still resolvable.
        requireNotNull(device)
        assertEquals(0x0110, device.bcdUvc)
        assertEquals(0, device.controlInterface)
    }

    @Test
    fun `one-byte descriptors stop parsing without reading a missing type byte`() {
        assertNull(UvcDescriptors.parse(byteArrayOf(1)))
        assertNull(UvcDescriptors.parse(byteArrayOf(1, 4)))

        val config = UvcTestDescriptors.build(bulk = true)
        val device = requireNotNull(UvcDescriptors.parse(config + byteArrayOf(1)))
        assertEquals(0x0110, device.bcdUvc)
        assertEquals(1, device.streaming.size)
    }

    @Test
    fun `every truncated prefix is safe to parse`() {
        val config = UvcTestDescriptors.build(bulk = true)
        for (length in 0..config.size) UvcDescriptors.parse(config.copyOf(length))
    }
}
