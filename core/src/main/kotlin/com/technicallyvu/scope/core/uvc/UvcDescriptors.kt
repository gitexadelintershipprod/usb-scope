package com.technicallyvu.scope.core.uvc

/** Kind of a UVC video streaming format, as narrowed from its class-specific subtype/GUID. */
enum class UvcFormatKind { MJPEG, YUY2, OTHER }

/** One VS_FRAME descriptor (uncompressed or MJPEG) belonging to a [UvcFormat]. */
data class UvcFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val defaultInterval100ns: Int,
    val maxFrameBufferSize: Int,
)

/** One VS_FORMAT descriptor (uncompressed or MJPEG) and its frame descriptors. */
data class UvcFormat(
    val index: Int,
    val kind: UvcFormatKind,
    val defaultFrameIndex: Int,
    val frames: List<UvcFrame>,
)

/** One standard endpoint descriptor attached to a streaming interface's alternate setting. */
data class UvcEndpoint(
    val address: Int,
    val isBulk: Boolean,
    val maxPacketSize: Int,
)

/** One alternate setting of a VideoStreaming interface, and the endpoints declared under it. */
data class UvcAltSetting(
    val alt: Int,
    val endpoints: List<UvcEndpoint>,
)

/** A VideoStreaming interface: its formats/frames (declared under alt 0) and its alt settings. */
data class UvcStreamingInterface(
    val number: Int,
    val inputEndpoint: Int,
    val formats: List<UvcFormat>,
    val altSettings: List<UvcAltSetting>,
)

/** A parsed UVC function: the VideoControl interface plus its VideoStreaming interface(s). */
data class UvcDevice(
    val bcdUvc: Int,
    val controlInterface: Int,
    val streaming: List<UvcStreamingInterface>,
)

/**
 * Parses a USB configuration descriptor byte blob (as returned by `GET_DESCRIPTOR`) looking for a
 * UVC function: a VideoControl interface (class 0x0E, subclass 0x01) with its VC_HEADER, and the
 * VideoStreaming interface(s) (class 0x0E, subclass 0x02) it declares.
 *
 * This is a defensive linear walk: unknown descriptor types are skipped, and parsing stops
 * cleanly (never throwing) the moment a record's declared `bLength` would run past the end of the
 * buffer -- callers may hand this a truncated read.
 */
object UvcDescriptors {

    private const val CS_INTERFACE = 0x24
    private const val DT_INTERFACE = 0x04
    private const val DT_ENDPOINT = 0x05

    private const val UVC_CLASS = 0x0E
    private const val VC_SUBCLASS = 0x01
    private const val VS_SUBCLASS = 0x02

    private const val VC_HEADER = 0x01

    private const val VS_INPUT_HEADER = 0x01
    private const val VS_FORMAT_UNCOMPRESSED = 0x04
    private const val VS_FRAME_UNCOMPRESSED = 0x05
    private const val VS_FORMAT_MJPEG = 0x06
    private const val VS_FRAME_MJPEG = 0x07

    /**
     * `guidFormat` for YUY2 as it appears **on the wire**. The GUID
     * `{32595559-0000-0010-8000-00AA00389B71}` is serialised with its `Data1`/`Data2`/`Data3`
     * fields little-endian, so the first four bytes read as ASCII "YUY2" (`59 55 59 32`) rather
     * than as the textual GUID's first group.
     */
    private val YUY2_GUID = byteArrayOf(
        0x59, 0x55, 0x59, 0x32, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    private class MutFormat(val index: Int, val kind: UvcFormatKind, val defaultFrameIndex: Int) {
        val frames = mutableListOf<UvcFrame>()
    }

    private class MutStreaming(val number: Int) {
        var inputEndpoint: Int = 0
        val formats = mutableListOf<MutFormat>()
        val altSettings = LinkedHashMap<Int, MutableList<UvcEndpoint>>()
    }

    fun parse(config: ByteArray): UvcDevice? {
        val size = config.size

        fun u8(off: Int) = config[off].toInt() and 0xFF
        fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
        fun u32(off: Int) = u8(off) or (u8(off + 1) shl 8) or (u8(off + 2) shl 16) or (u8(off + 3) shl 24)

        var bcdUvc = 0
        var controlInterface = 0
        var vcSeen = false

        var curNumber = -1
        var curAlt = 0
        var curClass = 0
        var curSubclass = 0

        val streamingMap = LinkedHashMap<Int, MutStreaming>()
        fun streamingFor(number: Int) = streamingMap.getOrPut(number) { MutStreaming(number) }

        var i = 0
        while (i < size) {
            val bLength = u8(i)
            // Every descriptor needs both bLength and bDescriptorType. A one-byte record,
            // especially at the end of a truncated reply, has no type byte to inspect.
            if (bLength < 2 || bLength > size - i) break
            val bDescriptorType = u8(i + 1)

            when (bDescriptorType) {
                DT_INTERFACE -> if (bLength >= 9) {
                    curNumber = u8(i + 2)
                    curAlt = u8(i + 3)
                    curClass = u8(i + 5)
                    curSubclass = u8(i + 6)
                    if (curClass == UVC_CLASS && curSubclass == VS_SUBCLASS) {
                        streamingFor(curNumber).altSettings.getOrPut(curAlt) { mutableListOf() }
                    }
                }

                DT_ENDPOINT -> if (bLength >= 7 && curClass == UVC_CLASS && curSubclass == VS_SUBCLASS) {
                    val address = u8(i + 2)
                    val attributes = u8(i + 3)
                    val maxPacketSize = u16(i + 4)
                    val isBulk = (attributes and 0x03) == 0x02
                    streamingFor(curNumber).altSettings
                        .getOrPut(curAlt) { mutableListOf() }
                        .add(UvcEndpoint(address, isBulk, maxPacketSize))
                }

                CS_INTERFACE -> if (bLength >= 3) {
                    val subtype = u8(i + 2)
                    if (curClass == UVC_CLASS && curSubclass == VC_SUBCLASS) {
                        if (subtype == VC_HEADER && bLength >= 5) {
                            bcdUvc = u16(i + 3)
                            controlInterface = curNumber
                            vcSeen = true
                        }
                    } else if (curClass == UVC_CLASS && curSubclass == VS_SUBCLASS) {
                        val streaming = streamingFor(curNumber)
                        when (subtype) {
                            VS_INPUT_HEADER -> if (bLength >= 7) {
                                streaming.inputEndpoint = u8(i + 6)
                            }

                            VS_FORMAT_MJPEG -> if (bLength >= 7) {
                                val index = u8(i + 3)
                                val defaultFrameIndex = u8(i + 6)
                                streaming.formats += MutFormat(index, UvcFormatKind.MJPEG, defaultFrameIndex)
                            }

                            VS_FORMAT_UNCOMPRESSED -> if (bLength >= 23) {
                                val index = u8(i + 3)
                                val guid = config.copyOfRange(i + 5, i + 21)
                                val kind = if (guid.contentEquals(YUY2_GUID)) UvcFormatKind.YUY2 else UvcFormatKind.OTHER
                                val defaultFrameIndex = u8(i + 22)
                                streaming.formats += MutFormat(index, kind, defaultFrameIndex)
                            }

                            VS_FRAME_MJPEG, VS_FRAME_UNCOMPRESSED -> if (bLength >= 25) {
                                val index = u8(i + 3)
                                val width = u16(i + 5)
                                val height = u16(i + 7)
                                val maxFrameBufferSize = u32(i + 17)
                                val defaultInterval = u32(i + 21)
                                streaming.formats.lastOrNull()?.frames?.add(
                                    UvcFrame(index, width, height, defaultInterval, maxFrameBufferSize),
                                )
                            }
                        }
                    }
                }
            }

            i += bLength
        }

        if (!vcSeen) return null

        val streaming = streamingMap.values.map { s ->
            UvcStreamingInterface(
                number = s.number,
                inputEndpoint = s.inputEndpoint,
                formats = s.formats.map { f -> UvcFormat(f.index, f.kind, f.defaultFrameIndex, f.frames.toList()) },
                altSettings = s.altSettings.map { (alt, endpoints) -> UvcAltSetting(alt, endpoints.toList()) },
            )
        }

        return UvcDevice(bcdUvc, controlInterface, streaming)
    }
}
