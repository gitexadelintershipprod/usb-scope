package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.FpsMeter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A configuration this driver understands the shape of but cannot drive (isochronous-only endpoints,
 * no MJPEG/YUY2 format, not a UVC device at all). Permanent, so [UvcBulkDriver.open] does not retry
 * it: the answer would be identical three times over, and the user would wait 4.5 s for it.
 */
class UvcUnsupportedException(message: String) : UsbException(message)

/**
 * Generic USB Video Class driver for cameras that stream over a **bulk** endpoint.
 *
 * Vendor-neutral: it matches on the standard interface classes rather than a USB id, so it is the
 * fallback for any UVC camera the vendor-specific drivers do not claim (it is last in
 * [com.technicallyvu.scope.core.driver.DriverRegistry]). Isochronous cameras -- the majority of
 * webcams -- are refused with an explanation rather than a mystery failure, because isochronous
 * transfers need a packet-scheduling API neither backend exposes yet.
 */
class UvcBulkDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "uvc-bulk"
    override val displayName = "UVC camera"
    override val defaultRotation: Int get() = 0

    /**
     * A UVC function is identified by its VideoControl interface (class 0x0E, subclass 0x01); spec
     * §13.1. The VideoStreaming interfaces are enumerated from the configuration descriptor rather
     * than from this summary, which on some platforms lists only the active configuration's
     * interfaces -- so a device that really has no streaming interface is reported by [open], with
     * a message, instead of silently failing to match here.
     */
    override fun matches(info: UsbDeviceInfo): Boolean =
        info.interfaces.any { it.usbClass == UVC_CLASS && it.subclass == VC_SUBCLASS }

    override fun withPacketSink(sink: PacketSink): DeviceDriver =
        UvcBulkDriver(clock, sleep, ioDispatcher, sink)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        for (attempt in 1..OPEN_ATTEMPTS) {
            val claimed = mutableListOf<Int>()
            try {
                val setup = handshake(transport, claimed)
                return UvcFrameSource(transport, setup, clock, ioDispatcher, packetSink)
            } catch (e: UvcUnsupportedException) {
                // Permanent: retrying cannot change the descriptors. Hand back anything the
                // half-finished handshake claimed, or the device stays locked to a dead session.
                claimed.asReversed().forEach { runCatching { transport.releaseInterface(it) } }
                throw e
            } catch (e: UsbException) {
                lastError = e
                // Best effort: a half-finished handshake may hold one or both interfaces, and the
                // next attempt's claim would then fail for a reason of our own making.
                claimed.asReversed().forEach { runCatching { transport.releaseInterface(it) } }
                // No device reset here: on libusb resetDevice() invalidates the handle we are about to
                // retry with, and Android has no reset at all. A plain wait is portable and sufficient.
                // Nothing waits after the final attempt -- the caller is about to see the failure.
                if (attempt < OPEN_ATTEMPTS) sleep(RETRY_WAIT_MS)
            }
        }
        throw UsbException("UVC handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    /** Everything [UvcFrameSource] needs, resolved from the descriptors and the probe/commit exchange. */
    internal data class Setup(
        val controlInterface: Int,
        val streamingInterface: Int,
        val altSetting: Int,
        val endpoint: Int,
        val kind: UvcFormatKind,
        val width: Int,
        val height: Int,
        val maxPayloadSize: Int,
        /** Negotiated `dwMaxVideoFrameSize`, else the frame descriptor's; 0 when neither declares one. */
        val maxFrameBufferSize: Int,
    )

    /**
     * Reads the descriptors, claims the interfaces and negotiates a format. Each interface number is
     * appended to [claimed] *before* it is claimed, so a caller can release on failure without
     * having to guess how far this got (a claim can fail after the device already took it).
     */
    internal fun handshake(t: UsbTransport, claimed: MutableList<Int> = mutableListOf()): Setup {
        val config = t.readConfigDescriptor()
        val device = UvcDescriptors.parse(config) ?: throw UvcUnsupportedException("not a UVC device")
        val vs = device.streaming.firstOrNull()
            ?: throw UvcUnsupportedException("this camera declares no video streaming interface")

        // Prefer alt 0: on a bulk camera the endpoint lives there and no alt switch is really needed.
        val (alt, endpoint) = vs.altSettings
            .sortedBy { it.alt }
            .firstNotNullOfOrNull { setting ->
                setting.endpoints
                    .firstOrNull { it.isBulk && (it.address and 0x80) != 0 }
                    ?.let { setting.alt to it.address }
            }
            ?: throw UvcUnsupportedException(
                "This camera has no bulk streaming endpoint (it streams over isochronous transfers, " +
                    "which are not supported yet)",
            )

        val format = vs.formats.firstOrNull { it.kind == UvcFormatKind.MJPEG }
            ?: vs.formats.firstOrNull { it.kind == UvcFormatKind.YUY2 }
            ?: throw UvcUnsupportedException("this camera offers no MJPEG/YUY2 format")
        val frame = format.frames.firstOrNull { it.index == format.defaultFrameIndex }
            ?: format.frames.firstOrNull()
            ?: throw UvcUnsupportedException("this camera's format declares no frame sizes")

        // Validate before starting a stream: YUYV filters run before Android's bitmap converter,
        // so rejecting huge dimensions only in the renderer is too late to bound allocations.
        // These limits match the Android renderer's decoded-frame limits.
        if (frame.width !in 1..MAX_FRAME_DIMENSION || frame.height !in 1..MAX_FRAME_DIMENSION ||
            frame.width.toLong() * frame.height.toLong() > MAX_FRAME_PIXELS
        ) {
            throw UvcUnsupportedException(
                "Camera frame ${frame.width}x${frame.height} exceeds the supported size " +
                    "(4096 per side, 8 megapixels). Use a lower-resolution camera.",
            )
        }

        claimed += device.controlInterface
        t.claimInterface(device.controlInterface)
        claimed += vs.number
        t.claimInterface(vs.number)

        val negotiated = UvcProbe.negotiate(
            t,
            vsInterface = vs.number,
            bcdUvc = device.bcdUvc,
            request = UvcProbeControl(
                formatIndex = format.index,
                frameIndex = frame.index,
                frameInterval100ns = frame.defaultInterval100ns,
                maxVideoFrameSize = frame.maxFrameBufferSize,
                // 0 = "no preference": the device fills in what it can actually sustain.
                maxPayloadTransferSize = 0,
            ),
        )

        // Always issued, alt 0 included: a camera that was left in another alt setting by a previous
        // session (or another app) will not start streaming until it is put back.
        t.setAltSetting(vs.number, alt)

        return Setup(
            controlInterface = device.controlInterface,
            streamingInterface = vs.number,
            altSetting = alt,
            endpoint = endpoint,
            kind = format.kind,
            width = frame.width,
            height = frame.height,
            maxPayloadSize = negotiated.maxPayloadTransferSize.takeIf { it > 0 } ?: DEFAULT_MAX_PAYLOAD,
            // What the device committed to, else what the frame descriptor declared; the parser
            // turns this into the ceiling that stops a wedged stream from eating the heap.
            maxFrameBufferSize = negotiated.maxVideoFrameSize.takeIf { it > 0 } ?: frame.maxFrameBufferSize,
        )
    }

    companion object {
        const val UVC_CLASS = 0x0E
        const val VC_SUBCLASS = 0x01
        const val VS_SUBCLASS = 0x02
        const val CHUNK_SIZE = 16384
        /** Short on purpose: a timeout costs almost nothing (n == 0 ends any payload in progress) and lets the loop notice close/cancel. */
        const val READ_TIMEOUT_MS = 500
        /** Used when the device commits a `dwMaxPayloadTransferSize` of 0, which some firmware does. */
        const val DEFAULT_MAX_PAYLOAD = 16384
        const val OPEN_ATTEMPTS = 3
        const val RETRY_WAIT_MS = 1500L
        private const val MAX_FRAME_DIMENSION = 4096
        private const val MAX_FRAME_PIXELS = 8_388_608L
    }
}

class UvcFrameSource internal constructor(
    private val transport: UsbTransport,
    private val setup: UvcBulkDriver.Setup,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    /**
     * Held around the native bulk read and around the release/close in [close], so that closing waits
     * for an in-flight transfer to return before the interface and the transport go away. Without it a
     * shutdown can free the USB context under a running libusb transfer and abort the JVM.
     */
    private val ioLock = ReentrantLock()

    // As in the other drivers, the blocking read is offloaded per-call with withContext rather than
    // flowOn: flowOn's buffer would let this loop race ahead of a downstream take(n)'s cancellation.
    override val frames: Flow<Frame> = flow {
        val parser = UvcPayloadParser(
            setup.kind,
            setup.width,
            setup.height,
            setup.maxPayloadSize,
            setup.maxFrameBufferSize,
        )
        val fps = FpsMeter()
        // Never larger than one payload: a read that spanned a payload boundary would hand the
        // parser the next payload's header as if it were data, and the parser only looks for a
        // header when a payload has ended. 16 KiB is the ceiling (the endpoint's practical maximum)
        // and 512 the floor, so a device that commits an absurdly small payload size still reads in
        // sensible units.
        val capacity = setup.maxPayloadSize.coerceIn(MIN_READ_SIZE, UvcBulkDriver.CHUNK_SIZE)
        val buf = ByteArray(capacity)
        var lastFps = 0.0
        fun snapshot() = StreamStats(
            fps = lastFps,
            framesEmitted = parser.framesEmitted,
            framesDropped = parser.framesDropped,
            bytesReceived = parser.bytesReceived,
            headerErrors = parser.badHeaders,
        )
        while (!closed) {
            val n = withContext(dispatcher) {
                ioLock.withLock {
                    if (closed) 0
                    else transport.bulkRead(setup.endpoint, buf, UvcBulkDriver.READ_TIMEOUT_MS)
                }
            }
            val now = clock()
            // n == 0 (a zero-length packet, or a read timeout) is handed to the parser too: mid
            // payload it ends that payload, and with nothing in progress the parser ignores it.
            if (n > 0) packetSink?.onPacket(buf, n, now)
            for (frame in parser.accept(buf, n, capacity, now)) {
                lastFps = fps.tick(now)
                _stats.value = snapshot()
                emit(frame)
            }
            // Bytes, drops and header errors move whether or not a frame completed. Publishing only
            // on an emitted frame freezes the overlay exactly when it is most wanted: on a stream
            // that is arriving but never completing a frame.
            val published = _stats.value
            if (published.framesEmitted != parser.framesEmitted ||
                published.framesDropped != parser.framesDropped ||
                published.bytesReceived != parser.bytesReceived ||
                published.headerErrors != parser.badHeaders
            ) {
                _stats.value = snapshot()
            }
        }
    }

    /** Non-suspending. Blocks only for as long as an in-flight read needs to return (READ_TIMEOUT_MS). */
    override fun close() {
        closed = true
        ioLock.withLock {
            // Parking the streaming interface at alt 0 tells the device to stop sending.
            runCatching { transport.setAltSetting(setup.streamingInterface, 0) }
            runCatching { transport.releaseInterface(setup.streamingInterface) }
            runCatching { transport.releaseInterface(setup.controlInterface) }
            runCatching { transport.close() }
        }
    }

    private companion object {
        /** Floor under the bulk read size, for a device that commits an implausibly small payload. */
        const val MIN_READ_SIZE = 512
    }
}
