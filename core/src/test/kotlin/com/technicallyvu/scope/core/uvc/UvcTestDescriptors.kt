package com.technicallyvu.scope.core.uvc

/** Little-endian byte-list helpers used only by the synthetic descriptor builder below. */
private fun MutableList<Byte>.raw(vararg values: Int) {
    values.forEach { add(it.toByte()) }
}

private fun MutableList<Byte>.raw(bytes: ByteArray) {
    bytes.forEach { add(it) }
}

private fun MutableList<Byte>.le16(v: Int) {
    add((v and 0xFF).toByte())
    add(((v shr 8) and 0xFF).toByte())
}

private fun MutableList<Byte>.le32(v: Int) {
    add((v and 0xFF).toByte())
    add(((v shr 8) and 0xFF).toByte())
    add(((v shr 16) and 0xFF).toByte())
    add(((v shr 24) and 0xFF).toByte())
}

/**
 * Assembles synthetic USB configuration descriptors for [UvcDescriptorsTest], standing in for a
 * real device since no UVC hardware is available in this phase.
 *
 * Byte layout produced by [build] (bulk = true, includeYuy2 = true; offsets in decimal):
 *
 * ```
 * [  0.. 8]  Configuration descriptor (9)            wTotalLength patched at [2..3]
 * [  9..16]  Interface Association Descriptor (8)    type 0x0B, first-if 0, count 2
 * [ 17..25]  VC interface, alt 0 (9)                 number 0, class 0x0E, sub 0x01
 * [ 26..38]  VC_HEADER (13)                          CS_INTERFACE 0x24 / subtype 0x01, bcdUVC, wTotalLength=13
 * [ 39..47]  VS interface, alt 0 (9)                 number 1, class 0x0E, sub 0x02, numEndpoints = bulk?1:0
 * [ 48..61]  VS_INPUT_HEADER (14)                    bNumFormats, wTotalLength patched at [+4..+5], bEndpointAddress 0x81
 * [ 62..72]  FORMAT_MJPEG (11)                       bFormatIndex 1, bDefaultFrameIndex 1
 * [ 73..102] FRAME_MJPEG 640x480 (30)                bFrameIndex 1, buffer 614400, interval 333333
 * [103..129] FORMAT_UNCOMPRESSED / YUY2 (27)          [only if includeYuy2] bFormatIndex 2, bDefaultFrameIndex 1
 * [130..159] FRAME_UNCOMPRESSED 320x240 (30)          [only if includeYuy2] bFrameIndex 1, buffer 153600
 *   bulk: [160..166] bulk IN endpoint (7)             address 0x81, maxPacket 512 — attaches to VS alt 0
 *   iso:  [160..168] VS interface, alt 1 (9)          number 1, alt 1, numEndpoints 1
 *         [169..175] isochronous IN endpoint (7)      address 0x81, maxPacket 1024 — attaches to VS alt 1
 * ```
 *
 * Without `includeYuy2` the FORMAT_UNCOMPRESSED/FRAME_UNCOMPRESSED pair (57 bytes) is omitted and
 * everything after it shifts down by 57 bytes; the two `wTotalLength` fields are always patched to
 * match whatever was actually emitted.
 */
object UvcTestDescriptors {

    /**
     * UVC FORMAT_UNCOMPRESSED `guidFormat` for YUY2 in wire order: `{32595559-...}` serialises
     * `Data1` little-endian, so the blob starts with ASCII "YUY2".
     */
    val YUY2_GUID = byteArrayOf(
        0x59, 0x55, 0x59, 0x32, 0x00, 0x00, 0x10, 0x00,
        0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
    )

    fun build(
        bulk: Boolean = true,
        bcdUvc: Int = 0x0110,
        includeYuy2: Boolean = true,
        /** The 16 `guidFormat` bytes to emit, so a test can hand in a literal blob of its own. */
        uncompressedGuid: ByteArray = YUY2_GUID,
        mjpegWidth: Int = 640,
        mjpegHeight: Int = 480,
    ): ByteArray {
        require(uncompressedGuid.size == 16) { "guidFormat must be 16 bytes" }
        val out = mutableListOf<Byte>()

        // Configuration descriptor (9) -- wTotalLength patched once the full length is known.
        val configLengthOffset = out.size + 2
        out.raw(0x09, 0x02)
        out.le16(0) // wTotalLength placeholder
        out.raw(0x02, 0x01, 0x00, 0x80, 0x32) // bNumInterfaces=2, bConfigurationValue=1, iConfiguration=0, bmAttributes, bMaxPower

        // Interface Association Descriptor (8)
        out.raw(0x08, 0x0B, 0x00, 0x02, 0x0E, 0x03, 0x00, 0x00)

        // VC interface, alt 0 (9)
        out.raw(0x09, 0x04, 0x00, 0x00, 0x00, 0x0E, 0x01, 0x00, 0x00)

        // VC_HEADER (13)
        out.raw(0x0D, 0x24, 0x01)
        out.le16(bcdUvc)
        out.le16(13) // wTotalLength (class-specific VC descriptors only)
        out.le32(6_000_000) // dwClockFrequency, arbitrary
        out.raw(0x01, 0x01) // bInCollection=1, baInterfaceNr(1)=1 (VS interface number)

        // VS interface, alt 0 (9)
        val vsNumEndpoints = if (bulk) 1 else 0
        out.raw(0x09, 0x04, 0x01, 0x00, vsNumEndpoints, 0x0E, 0x02, 0x00, 0x00)

        // VS_INPUT_HEADER (14) -- wTotalLength patched once formats+frames are known.
        val inputHeaderStart = out.size
        val inputHeaderTotalLengthOffset = inputHeaderStart + 4
        val numFormats = if (includeYuy2) 2 else 1
        out.raw(0x0E, 0x24, 0x01, numFormats)
        out.le16(0) // wTotalLength placeholder
        out.raw(0x81, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00)

        // FORMAT_MJPEG (11): index 1, default frame 1
        out.raw(0x0B, 0x24, 0x06, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00)

        // FRAME_MJPEG 640x480 (30)
        out.raw(0x1E, 0x24, 0x07, 0x01, 0x00)
        out.le16(mjpegWidth)
        out.le16(mjpegHeight)
        out.le32(0) // dwMinBitRate
        out.le32(0) // dwMaxBitRate
        out.le32(614_400) // dwMaxVideoFrameBufferSize
        out.le32(333_333) // dwDefaultFrameInterval
        out.raw(0x01) // bFrameIntervalType
        out.le32(333_333) // dwFrameInterval(1)

        if (includeYuy2) {
            // FORMAT_UNCOMPRESSED / YUY2 (27): index 2, default frame 1
            out.raw(0x1B, 0x24, 0x04, 0x02, 0x01)
            out.raw(uncompressedGuid)
            out.raw(0x10, 0x01, 0x00, 0x00, 0x00, 0x00)

            // FRAME_UNCOMPRESSED 320x240 (30)
            out.raw(0x1E, 0x24, 0x05, 0x01, 0x00)
            out.le16(320)
            out.le16(240)
            out.le32(0)
            out.le32(0)
            out.le32(153_600)
            out.le32(333_333)
            out.raw(0x01)
            out.le32(333_333)
        }

        val inputHeaderTotalLength = out.size - inputHeaderStart

        if (bulk) {
            // Bulk IN endpoint (7), attaches to VS alt 0
            out.raw(0x07, 0x05, 0x81, 0x02)
            out.le16(512)
            out.raw(0x00)
        } else {
            // VS interface, alt 1 (9) carrying the isochronous endpoint
            out.raw(0x09, 0x04, 0x01, 0x01, 0x01, 0x0E, 0x02, 0x00, 0x00)
            out.raw(0x07, 0x05, 0x81, 0x05)
            out.le16(1024)
            out.raw(0x01)
        }

        val bytes = out.toByteArray()
        bytes[configLengthOffset] = (bytes.size and 0xFF).toByte()
        bytes[configLengthOffset + 1] = ((bytes.size shr 8) and 0xFF).toByte()
        bytes[inputHeaderTotalLengthOffset] = (inputHeaderTotalLength and 0xFF).toByte()
        bytes[inputHeaderTotalLengthOffset + 1] = ((inputHeaderTotalLength shr 8) and 0xFF).toByte()
        return bytes
    }

    /** A plain HID-only configuration with no video control interface, for the negative test. */
    fun withoutVc(): ByteArray {
        val out = mutableListOf<Byte>()
        val configLengthOffset = out.size + 2
        out.raw(0x09, 0x02)
        out.le16(0)
        out.raw(0x01, 0x01, 0x00, 0x80, 0x32) // bNumInterfaces=1
        // HID interface, alt 0 (9)
        out.raw(0x09, 0x04, 0x00, 0x00, 0x01, 0x03, 0x01, 0x02, 0x00)

        val bytes = out.toByteArray()
        bytes[configLengthOffset] = (bytes.size and 0xFF).toByte()
        bytes[configLengthOffset + 1] = ((bytes.size shr 8) and 0xFF).toByte()
        return bytes
    }
}
