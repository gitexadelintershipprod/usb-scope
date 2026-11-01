package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.IsochronousStream
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.FpsMeter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A configuration this driver understands the shape of but cannot drive on this backend (unsupported endpoints,
 * no MJPEG/YUY2 format, not a UVC device at all). Permanent, so [UvcBulkDriver.open] does not retry
 * it: the answer would be identical three times over, and the user would wait 4.5 s for it.
 */
class UvcUnsupportedException(message: String) : UsbException(message)

/**
 * Generic USB Video Class driver for bulk or supported native isochronous IN streaming.
 *
 * Vendor-neutral: it matches standard interface classes and remains the fallback after vendor
 * drivers. The historical id is retained for saved settings. Platforms without queued ISO support
 * continue to refuse ISO-only devices with an explicit message.
 */
class UvcBulkDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
    private val streamTimeoutMs: Long = 10_000,
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
        UvcBulkDriver(clock, sleep, ioDispatcher, sink, streamTimeoutMs)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        for (attempt in 1..OPEN_ATTEMPTS) {
            val claimed = mutableListOf<Int>()
            try {
                val setup = handshake(transport, claimed)
                val iso = if (setup.isIsochronous) transport.openIsochronous(setup.endpoint, setup.packetSize) else null
                return UvcFrameSource(transport, setup, clock, ioDispatcher, packetSink, iso, streamTimeoutMs)
            } catch (e: UvcUnsupportedException) {
                // Permanent: retrying cannot change the descriptors. Hand back anything the
                // half-finished handshake claimed, or the device stays locked to a dead session.
                if (claimed.size > 1) runCatching { transport.setAltSetting(claimed.last(), 0) }
                claimed.asReversed().forEach { runCatching { transport.releaseInterface(it) } }
                throw e
            } catch (e: UsbException) {
                lastError = e
                if (claimed.size > 1) runCatching { transport.setAltSetting(claimed.last(), 0) }
                // Best effort: a half-finished handshake may hold one or both interfaces, and the
                // next attempt's claim would then fail for a reason of our own making.
                claimed.asReversed().forEach { runCatching { transport.releaseInterface(it) } }
                // No device reset here: on libusb resetDevice() invalidates the handle we are about to
                // retry with, and Android has no reset at all. A plain wait is portable and sufficient.
                // Nothing waits after the final attempt -- the caller is about to see the failure.
                if (attempt < OPEN_ATTEMPTS) sleep(RETRY_WAIT_MS)
            }
        }
        throw UsbException("UVC handshake failed after $OPEN_ATTEMPTS attempts: ${lastError?.message ?: "USB error"}", cause = lastError)
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
        val isIsochronous: Boolean = false,
        val packetSize: Int = 0,
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

        // A bulk-capable camera keeps the existing portable path. ISO is selected only when
        // necessary and only if the platform can preserve individual packet completions.
        val inputEndpoints = vs.altSettings.sortedBy { it.alt }.flatMap { setting ->
            setting.endpoints.filter {
                (it.address and 0x80) != 0 && (vs.inputEndpoint == 0 || it.address == vs.inputEndpoint)
            }.map { setting.alt to it }
        }
        val bulk = inputEndpoints.firstOrNull { it.second.isBulk }
        val isoChoices = inputEndpoints.filter { it.second.validIsochronousSize }
        val isIsochronous = bulk == null
        if (isIsochronous && isoChoices.isEmpty()) {
            throw UvcUnsupportedException("This camera has no supported bulk or isochronous IN streaming endpoint")
        }
        if (isIsochronous && !t.supportsIsochronous) {
            throw UvcUnsupportedException("This camera needs isochronous USB transfers, unavailable on this platform")
        }

        val format = vs.formats.firstOrNull { it.kind == UvcFormatKind.MJPEG }
            ?: vs.formats.firstOrNull { it.kind == UvcFormatKind.YUY2 }
            ?: throw UvcUnsupportedException("this camera offers no MJPEG/YUY2 format")
        val defaultFrame = format.frames.firstOrNull { it.index == format.defaultFrameIndex }
            ?: format.frames.firstOrNull()
            ?: throw UvcUnsupportedException("this camera's format declares no frame sizes")
        val (frame, frameInterval) = if (isIsochronous) selectIsoFrame(format, isoChoices.map { it.second })
            else defaultFrame to defaultFrame.defaultInterval100ns

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
        if (isIsochronous) t.setAltSetting(vs.number, 0)

        val negotiated = UvcProbe.negotiate(
            t,
            vsInterface = vs.number,
            bcdUvc = device.bcdUvc,
            request = UvcProbeControl(
                formatIndex = format.index,
                frameIndex = frame.index,
                frameInterval100ns = frameInterval,
                maxVideoFrameSize = frame.maxFrameBufferSize,
                // 0 = "no preference": the device fills in what it can actually sustain.
                maxPayloadTransferSize = 0,
            ),
        )

        // Choose the smallest alternate that can carry the payload the camera committed to.
        // If firmware declares zero, reserve the largest valid advertised alternate instead.
        val selected = bulk ?: isoChoices
            .sortedWith(compareBy<Pair<Int, UvcEndpoint>> { it.second.effectivePacketSize }.thenBy { it.first })
            .let { choices ->
                val wanted = negotiated.maxPayloadTransferSize
                if (wanted > 0) choices.firstOrNull { it.second.effectivePacketSize >= wanted }
                    ?: throw UvcUnsupportedException(
                        "Camera requires $wanted bytes per isochronous interval; available maximum is " +
                            choices.maxOf { it.second.effectivePacketSize },
                    )
                else choices.last()
            }
        val (alt, endpoint) = selected
        // Always issued, including bulk alt 0, so previous sessions cannot leave a stale alternate.
        t.setAltSetting(vs.number, alt)

        return Setup(
            controlInterface = device.controlInterface,
            streamingInterface = vs.number,
            altSetting = alt,
            endpoint = endpoint.address,
            kind = format.kind,
            width = frame.width,
            height = frame.height,
            maxPayloadSize = negotiated.maxPayloadTransferSize.takeIf { it > 0 } ?: DEFAULT_MAX_PAYLOAD,
            // What the device committed to, else what the frame descriptor declared; the parser
            // turns this into the ceiling that stops a wedged stream from eating the heap.
            maxFrameBufferSize = negotiated.maxVideoFrameSize.takeIf { it > 0 } ?: frame.maxFrameBufferSize,
            isIsochronous = isIsochronous,
            packetSize = if (isIsochronous) endpoint.effectivePacketSize else 0,
        )
    }

    /** Prefer an advertised VGA-or-smaller preview; never invent dimensions or frame intervals. */
    internal fun selectIsoFrame(format: UvcFormat, endpoints: List<UvcEndpoint>): Pair<UvcFrame, Int> {
        val valid = format.frames.filter {
            it.width in 1..MAX_FRAME_DIMENSION && it.height in 1..MAX_FRAME_DIMENSION &&
                it.width.toLong() * it.height <= MAX_FRAME_PIXELS && it.defaultInterval100ns > 0
        }
        if (valid.isEmpty()) throw UvcUnsupportedException("This camera declares no safe preview frame size")
        val preview = valid.filter { it.width <= 640 && it.height <= 480 }
        val ordered = if (preview.isNotEmpty()) preview.sortedByDescending { it.width.toLong() * it.height }
            else valid.sortedBy { it.width.toLong() * it.height }
        for (candidate in ordered) {
            if (format.kind != UvcFormatKind.YUY2) return candidate to candidate.defaultInterval100ns
            // USB2 high-bandwidth or 1024-byte service packets identify high speed. Otherwise use
            // the conservative full-speed budget; this does not assume a fast phone means fast USB.
            val bytesPerSecond = endpoints.maxOf { endpoint ->
                val highSpeed = endpoint.maxPacketSize > 1023
                val intervalsPerSecond = if (highSpeed) 8000L else 1000L
                val divisor = 1L shl (endpoint.interval.coerceIn(1, 16) - 1)
                (endpoint.effectivePacketSize - 12).coerceAtLeast(1).toLong() * intervalsPerSecond / divisor
            }
            val frameBytes = candidate.width.toLong() * candidate.height * 2
            val required = (frameBytes * 10_000_000 + bytesPerSecond - 1) / bytesPerSecond.coerceAtLeast(1)
            val intervals = (candidate.intervals100ns + candidate.defaultInterval100ns).filter { it > 0 }.distinct().sorted()
            intervals.firstOrNull { it.toLong() >= maxOf(required, candidate.defaultInterval100ns.toLong()) }
                ?.let { return candidate to it }
            if (candidate.minInterval100ns > 0 && candidate.maxInterval100ns >= candidate.minInterval100ns) {
                val step = candidate.intervalStep100ns.coerceAtLeast(1).toLong()
                val minimum = candidate.minInterval100ns.toLong()
                val wanted = maxOf(required, candidate.defaultInterval100ns.toLong(), minimum)
                val rounded = minimum + ((wanted - minimum + step - 1) / step) * step
                if (rounded <= candidate.maxInterval100ns) return candidate to rounded.toInt()
            }
        }
        throw UvcUnsupportedException("No advertised YUY2 preview interval fits this camera's isochronous bandwidth")
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
    private val isoStream: IsochronousStream? = null,
    private val streamTimeoutMs: Long = 10_000,
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
    private val bulkFrames: Flow<Frame> = flow {
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

    private val isoFrames: Flow<Frame> = flow {
        val reader = requireNotNull(isoStream)
        val parser = UvcPayloadParser(setup.kind, setup.width, setup.height, setup.maxPayloadSize, setup.maxFrameBufferSize)
        val fps = FpsMeter()
        val packet = ByteArray(setup.packetSize)
        var lastFps = 0.0
        val timeoutNanos = streamTimeoutMs.coerceAtLeast(1) * 1_000_000L
        var lastPacketAt = System.nanoTime()
        var lastFrameAt = lastPacketAt
        var lastStatsAt = lastPacketAt
        while (!closed) {
            currentCoroutineContext().ensureActive()
            val n = ioLock.withLock {
                if (closed) 0 else reader.readPacket(packet, UvcBulkDriver.READ_TIMEOUT_MS)
            }
            val now = clock()
            val monotonic = System.nanoTime()
            if (n > 0) {
                lastPacketAt = monotonic
                packetSink?.onPacket(packet, n, now)
            }
            val completed = parser.acceptIsochronousPacket(packet, n, now)
            for (frame in completed) {
                lastFrameAt = monotonic
                lastFps = fps.tick(now)
                _stats.value = StreamStats(
                    fps = lastFps, framesEmitted = parser.framesEmitted, framesDropped = parser.framesDropped,
                    bytesReceived = parser.bytesReceived, headerErrors = parser.badHeaders,
                )
                lastStatsAt = monotonic
                emit(frame)
            }
            // A high-speed ISO endpoint can complete 8000 packets/s. Publish per frame or four
            // times/s during a damaged/stalled stream instead of allocating UI stats per packet.
            if (monotonic - lastStatsAt >= 250_000_000L) {
                _stats.value = StreamStats(
                    fps = lastFps, framesEmitted = parser.framesEmitted, framesDropped = parser.framesDropped,
                    bytesReceived = parser.bytesReceived, headerErrors = parser.badHeaders,
                )
                lastStatsAt = monotonic
            }
            if (monotonic - lastPacketAt >= timeoutNanos) {
                throw UsbException("No successful isochronous USB packets for ${streamTimeoutMs}ms; reconnect the camera")
            }
            if (monotonic - lastFrameAt >= timeoutNanos) {
                throw UsbException("No complete UVC ${setup.kind} ${setup.width}x${setup.height} frame for ${streamTimeoutMs}ms: " +
                    "packetSize=${setup.packetSize}, bytes=${parser.bytesReceived}, " +
                    "badHeaders=${parser.badHeaders}, dropped=${parser.framesDropped}")
            }
        }
    }.flowOn(dispatcher).buffer(1, BufferOverflow.DROP_OLDEST)

    // ISO reads must continue while display/filtering handles the previous frame. UVC carries no
    // cable-button side channel, so replacing an old frame is safe. Keep bulk's replay-exact flow.
    override val frames: Flow<Frame> = if (isoStream == null) bulkFrames else isoFrames

    /** Non-suspending. Blocks only for as long as an in-flight read needs to return (READ_TIMEOUT_MS). */
    override fun close() {
        closed = true
        ioLock.withLock {
            // Every native ISO URB must be cancelled/reaped before changing alternates or freeing fd.
            runCatching { isoStream?.close() }
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
