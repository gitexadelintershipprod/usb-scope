package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.usb.ISO_PACKET_ERROR
import com.technicallyvu.scope.core.useeplus.FrameReassembler
import java.io.ByteArrayOutputStream

/**
 * Turns UVC bulk transfers or individual isochronous packets into frames.
 *
 * The stream is a sequence of *payload transfers*, each `bHeaderLength` header bytes (byte 0) plus
 * data, and each no longer than the negotiated `dwMaxPayloadTransferSize` ([maxPayloadSize]). A
 * payload ends at that size or, earlier, at a short USB packet. Because a payload may be larger than
 * one bulk read, [accept] tracks how much of the current payload is still outstanding and parses a
 * header only when a new payload begins.
 *
 * Frame boundaries come from `bmHeaderInfo` (byte 1): bit 0 is the frame id, which toggles between
 * consecutive frames; bit 1 (EOF) marks the last payload of a frame; bit 6 (ERR) means the device
 * knows the frame is damaged, so we drop it. A frame id toggle also ends a frame, which is how a
 * stream with a lost EOF still makes progress.
 *
 * The in-progress frame is bounded: a device that never sets EOF and never toggles the frame id
 * would otherwise grow [frameBuf] without limit until the process dies. Past [frameCap] the frame
 * is discarded and the parser waits for the next frame boundary to resynchronise, so a wedged
 * stream costs a counter rather than the heap.
 *
 * Not thread-safe; owned by one read loop.
 */
class UvcPayloadParser(
    private val kind: UvcFormatKind,
    private val width: Int,
    private val height: Int,
    private val maxPayloadSize: Int,
    /**
     * The negotiated (or descriptor) `dwMaxVideoFrameBufferSize`. 0 means "not declared", and a
     * size is then derived from [kind] and the frame dimensions.
     */
    maxFrameBufferSize: Int = 0,
) {
    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    /** Payload headers that were not headers at all (`bHeaderLength` < 2 or past the read). */
    var badHeaders: Long = 0; private set

    private val frameBuf = ByteArrayOutputStream(256 * 1024)

    /**
     * Hard ceiling on the bytes buffered for one frame: twice the larger of the declared frame size
     * and a 64 KiB floor. The floor is applied *before* the doubling, so the smallest cap this can
     * produce is 128 KiB however tiny the declared frame is. An absolute 32 MiB ceiling also
     * applies because the descriptor and negotiated sizes come from an untrusted USB device.
     * A legitimately over-long frame (a
     * device that pads, or a JPEG that beats the declared maximum) still arrives; a runaway stream
     * is cut off early.
     */
    private val frameCap: Long = run {
        val declared: Long = if (maxFrameBufferSize > 0) {
            maxFrameBufferSize.toLong()
        } else when (kind) {
            UvcFormatKind.YUY2 -> width.toLong() * height.toLong() * 2 + 65_536
            else -> 4L * width.toLong() * height.toLong()
        }
        minOf(maxOf(declared, MIN_FRAME_CAP) * 2, MAX_FRAME_CAP)
    }

    /** Bytes still expected in the payload being read; 0 means the next chunk starts a new payload. */
    private var remainingInPayload = 0
    private var currentFid = -1
    private var frameErrored = false

    /**
     * The current frame blew past [frameCap] and has already been counted as dropped; its bytes are
     * discarded until the next frame boundary rather than counted a second time.
     */
    private var frameDiscarded = false

    /** Bytes of the in-progress frame currently buffered. Diagnostic; used by the tests. */
    val bufferedBytes: Int get() = frameBuf.size()

    /** The current payload's header said EOF; the frame ends once its data has all arrived. */
    private var eofPending = false

    /**
     * Consumes [n] bytes of [chunk] read from the bulk endpoint with a buffer of [chunkCapacity]
     * bytes (a read shorter than the buffer is a short packet, and so ends the payload). Returns the
     * frames completed by this chunk -- normally none or one.
     *
     * [n] of 0 is a legitimate event, not a no-op: a zero-length packet or a read timeout in the
     * middle of a payload ends that payload, exactly as a short packet does. With no payload in
     * progress it is ignored, so a read loop can hand every read straight to this method.
     */
    fun accept(chunk: ByteArray, n: Int, chunkCapacity: Int, nowNanos: Long): List<Frame> {
        if (n < 0) return emptyList()
        if (n == 0) {
            // ZLP or timeout mid-payload: the device has nothing more for this payload.
            if (remainingInPayload <= 0) return emptyList()
            val out = mutableListOf<Frame>()
            endPayload(nowNanos, out)
            return out
        }
        bytesReceived += n
        val out = mutableListOf<Frame>()

        if (remainingInPayload > 0) {
            // Continuation of the payload already in progress: all of it is data.
            appendData(chunk, 0, n)
            remainingInPayload -= n
            if (n < chunkCapacity || remainingInPayload <= 0) endPayload(nowNanos, out)
            return out
        }

        if (!startPayload(chunk, n, nowNanos, out)) return out

        remainingInPayload = maxPayloadSize - n
        if (n < chunkCapacity || remainingInPayload <= 0) endPayload(nowNanos, out)
        return out
    }

    /**
     * One complete service-interval packet, preserving its USB boundary. Each packet starts with
     * its own UVC payload header even when it fills the endpoint capacity; bulk continuation
     * heuristics must never concatenate that header into image data. A failed packet damages the
     * current frame until EOF or the next frame id; a timeout/empty packet changes no framing.
     */
    fun acceptIsochronousPacket(chunk: ByteArray, n: Int, nowNanos: Long): List<Frame> {
        if (n == ISO_PACKET_ERROR) {
            frameErrored = true
            return emptyList()
        }
        if (n <= 0) return emptyList()
        require(n <= chunk.size) { "isochronous packet is larger than its buffer" }
        bytesReceived += n
        val out = mutableListOf<Frame>()
        if (startPayload(chunk, n, nowNanos, out)) endPayload(nowNanos, out)
        return out
    }

    private fun startPayload(chunk: ByteArray, n: Int, nowNanos: Long, out: MutableList<Frame>): Boolean {
        val headerLength = chunk[0].toInt() and 0xFF
        if (headerLength < 2 || headerLength > n) {
            // Not a payload header. Drop this transfer and wait for the next one to resynchronise.
            // A frame already under way has lost bytes, so it is marked damaged and will be dropped
            // at its end instead of emitted short.
            badHeaders++
            if (frameBuf.size() > 0 || frameDiscarded) frameErrored = true
            remainingInPayload = 0
            return false
        }

        val flags = chunk[1].toInt() and 0xFF
        val fid = flags and FID
        if (currentFid >= 0 && fid != currentFid &&
            (frameBuf.size() > 0 || frameDiscarded || frameErrored)
        ) finish(nowNanos, out)
        currentFid = fid
        if (flags and ERR != 0) frameErrored = true
        if (headerLength < n) appendData(chunk, headerLength, n - headerLength)
        eofPending = flags and EOF != 0

        return true
    }

    /**
     * Appends payload data to the frame under construction, enforcing [frameCap]. Once the cap is
     * hit the frame is counted as dropped exactly once and its bytes are thrown away until the next
     * FID toggle or EOF resynchronises the stream.
     */
    private fun appendData(chunk: ByteArray, offset: Int, length: Int) {
        if (frameDiscarded) return
        if (frameBuf.size().toLong() + length > frameCap) {
            framesDropped++
            frameErrored = true
            frameDiscarded = true
            frameBuf.reset()
            return
        }
        frameBuf.write(chunk, offset, length)
    }

    /**
     * The payload transfer is over. EOF is honoured here rather than at the header so that a frame
     * whose last payload spans several reads is emitted whole instead of truncated at the header.
     */
    private fun endPayload(nowNanos: Long, out: MutableList<Frame>) {
        remainingInPayload = 0
        if (eofPending) finish(nowNanos, out)
    }

    private fun finish(nowNanos: Long, out: MutableList<Frame>) {
        val discarded = frameDiscarded
        val bytes = frameBuf.toByteArray()
        frameBuf.reset()
        frameDiscarded = false
        val errored = frameErrored
        frameErrored = false
        eofPending = false
        // An over-long frame was already counted when the cap was hit; ending it counts nothing.
        if (discarded) return
        if (bytes.isEmpty()) return
        if (errored) {
            framesDropped++
            return
        }

        val data = when (kind) {
            UvcFormatKind.MJPEG -> if (FrameReassembler.isJpeg(bytes)) FrameData.Jpeg(bytes) else null
            UvcFormatKind.YUY2 -> if (width > 0 && height > 0 &&
                bytes.size.toLong() == width.toLong() * height.toLong() * 2
            ) FrameData.Yuyv422(width, height, bytes) else null
            UvcFormatKind.OTHER -> null
        }
        if (data == null) {
            framesDropped++
            return
        }
        framesEmitted++
        out += Frame(
            data = data,
            timestampNanos = nowNanos,
            // UVC carries no vendor button/sensor side channel; those stay at their neutral values.
            buttonPressed = false,
            cameraNumber = 0,
            sensorValue = 0,
        )
    }

    private companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val ERR = 0x40

        /** Floor under the declared frame size, so a tiny or undeclared format still gets room. */
        const val MIN_FRAME_CAP = 65_536L
        const val MAX_FRAME_CAP = 32L * 1024 * 1024
    }
}
