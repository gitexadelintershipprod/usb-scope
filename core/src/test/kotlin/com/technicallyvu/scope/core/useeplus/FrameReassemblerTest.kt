package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.TestPackets.packet
import com.technicallyvu.scope.core.jpegBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameReassemblerTest {
    private fun chunk(bytes: ByteArray) = UseeplusPacket.parse(bytes)!!

    @Test
    fun `emits the previous frame when the frame id changes`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        val (head, tail) = TestPackets.framePackets(frameId = 1, frame = jpeg)
        assertNull(r.accept(chunk(head), 100))
        assertNull(r.accept(chunk(tail), 200))
        val next = packet(7, frameId = 2, payload = TestPackets.SOI)
        val frame = r.accept(chunk(next), 300)!!
        assertArrayEquals(jpeg, frame.jpegBytes())
        assertEquals(300, frame.timestampNanos)
        assertEquals(1, r.framesEmitted)
        assertEquals(0, r.framesDropped)
    }

    @Test
    fun `drops a frame without an end marker and counts it`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(7, frameId = 1, payload = TestPackets.SOI + byteArrayOf(1, 2, 3))), 0)
        assertNull(r.accept(chunk(packet(7, frameId = 2, payload = TestPackets.SOI)), 0))
        assertEquals(0, r.framesEmitted)
        assertEquals(1, r.framesDropped)
    }

    @Test
    fun `drops a frame without a start marker`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(11, frameId = 1, payload = byteArrayOf(1, 2) + TestPackets.EOI)), 0)
        assertNull(r.accept(chunk(packet(7, frameId = 2, payload = TestPackets.SOI)), 0))
        assertEquals(1, r.framesDropped)
    }

    @Test
    fun `button flag on any chunk marks the frame`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        val half = jpeg.size / 2
        r.accept(chunk(packet(7, 1, jpeg.copyOfRange(0, half), flags = 0)), 0)
        r.accept(chunk(packet(11, 1, jpeg.copyOfRange(half, jpeg.size), flags = UseeplusPacket.BUTTON_MASK)), 0)
        val frame = r.accept(chunk(packet(7, 2, TestPackets.SOI)), 0)!!
        assertTrue(frame.buttonPressed)

        r.accept(chunk(packet(11, 2, jpeg.copyOfRange(2, jpeg.size))), 0)
        val second = r.accept(chunk(packet(7, 3, TestPackets.SOI)), 0)!!
        assertFalse(second.buttonPressed)
    }

    @Test
    fun `carries camera number and sensor value from the frame`() {
        val r = FrameReassembler()
        val jpeg = TestPackets.fakeJpeg()
        r.accept(chunk(packet(7, 1, jpeg, cameraNumber = 3, sensor = 0xABCD)), 0)
        val frame = r.accept(chunk(packet(7, 2, TestPackets.SOI)), 0)!!
        assertEquals(3, frame.cameraNumber)
        assertEquals(0xABCDL, frame.sensorValue)
    }

    @Test
    fun `counts payload bytes`() {
        val r = FrameReassembler()
        r.accept(chunk(packet(7, 1, ByteArray(10))), 0)
        r.accept(chunk(packet(11, 1, ByteArray(5))), 0)
        assertEquals(15, r.bytesReceived)
    }

    @Test
    fun `a constant frame id cannot grow the buffer without limit and the next frame recovers`() {
        val r = FrameReassembler()
        val data = ByteArray(16_000) { 0x5A }
        repeat(600) {
            assertNull(r.accept(chunk(packet(7, 1, data)), 0))
            assertTrue(r.bufferedBytes <= FrameReassembler.MAX_FRAME_BYTES)
        }
        assertEquals(1, r.framesDropped, "an oversized frame is counted only once")
        assertEquals(0, r.framesEmitted)
        assertEquals(0, r.bufferedBytes)

        val jpeg = TestPackets.fakeJpeg()
        assertNull(r.accept(chunk(packet(7, 2, jpeg)), 1))
        val recovered = r.accept(chunk(packet(7, 3, TestPackets.SOI)), 2)!!
        assertArrayEquals(jpeg, recovered.jpegBytes())
        assertEquals(1, r.framesEmitted)
        assertEquals(1, r.framesDropped)
    }

    @Test
    fun `isJpeg requires both markers`() {
        assertTrue(FrameReassembler.isJpeg(TestPackets.fakeJpeg()))
        assertFalse(FrameReassembler.isJpeg(TestPackets.SOI + byteArrayOf(0)))
        assertFalse(FrameReassembler.isJpeg(byteArrayOf(0) + TestPackets.EOI))
        assertFalse(FrameReassembler.isJpeg(ByteArray(0)))
    }
}
