package com.technicallyvu.scope.usb

/** The slice of android.hardware.usb.UsbDeviceConnection the transport uses, kept as an interface so it can be faked in JVM tests. */
interface UsbConnection : AutoCloseable {
    /** Android-granted file descriptor; -1 on transports that cannot expose native USB I/O. */
    val fileDescriptor: Int get() = -1
    fun claimInterface(number: Int): Boolean
    fun releaseInterface(number: Int): Boolean
    fun setInterface(number: Int, alt: Int): Boolean
    /**
     * Android semantics: bytes transferred, or -1 on timeout OR error.
     * Implementations throw [IllegalArgumentException] for an endpoint address the device does not
     * expose (e.g. a wiring mistake); the transport maps that into a UsbException rather than
     * letting it look like an ordinary transfer timeout.
     */
    fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int

    /**
     * The device's raw descriptors as the kernel already read them: the 18-byte device descriptor
     * followed by the configuration descriptor(s). Null when the platform cannot supply them, in
     * which case the caller falls back to GET_DESCRIPTOR control transfers.
     */
    fun rawDescriptors(): ByteArray?
}
