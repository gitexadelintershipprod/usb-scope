package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import java.io.ByteArrayOutputStream

/**
 * Accumulates payload chunks while the frame id is unchanged. When the id changes, the
 * accumulated bytes are emitted as a [Frame] if they carry JPEG SOI/EOI markers, else dropped.
 * Frames are capped at 4 MiB, well above the supported camera's 640x480 JPEGs. A device that
 * stops changing its frame id cannot grow the buffer indefinitely. Not thread-safe.
 */
class FrameReassembler {
    private val buffer = ByteArrayOutputStream(64 * 1024)
    private var currentFrameId = -1
    private var flagsOr = 0
    private var cameraNumber = 0
    private var sensorValue = 0L
    private var frameDiscarded = false

    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    internal val bufferedBytes: Int get() = buffer.size()

    /** Returns the completed previous frame, if this chunk started a new one and the previous was valid. */
    fun accept(chunk: UseeplusChunk, nowNanos: Long): Frame? {
        bytesReceived += chunk.payload.size
        var completed: Frame? = null
        if (chunk.frameId != currentFrameId) {
            if (buffer.size() > 0) completed = finish(nowNanos)
            currentFrameId = chunk.frameId
            flagsOr = 0
            frameDiscarded = false
        }
        if (!frameDiscarded) {
            if (buffer.size().toLong() + chunk.payload.size > MAX_FRAME_BYTES) {
                buffer.reset()
                frameDiscarded = true
                framesDropped++
            } else {
                buffer.write(chunk.payload)
            }
        }
        flagsOr = flagsOr or chunk.flags
        cameraNumber = chunk.cameraNumber
        sensorValue = chunk.sensorValue
        return completed
    }

    private fun finish(nowNanos: Long): Frame? {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return if (isJpeg(bytes)) {
            framesEmitted++
            Frame(
                data = FrameData.Jpeg(bytes),
                timestampNanos = nowNanos,
                buttonPressed = (flagsOr and UseeplusPacket.BUTTON_MASK) != 0,
                cameraNumber = cameraNumber,
                sensorValue = sensorValue,
            )
        } else {
            framesDropped++
            null
        }
    }

    companion object {
        internal const val MAX_FRAME_BYTES = 4 * 1024 * 1024

        fun isJpeg(b: ByteArray): Boolean =
            b.size >= 4 &&
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
                b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
    }
}
