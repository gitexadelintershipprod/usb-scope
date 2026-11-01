package com.technicallyvu.scope.usb

import java.io.IOException

/** Small usbfs bridge using only the descriptor already authorized by Android's USB service. */
object NativeIsochronous {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("scope_iso") }.isSuccess
    }

    fun open(fd: Int, endpoint: Int, packetSize: Int): Long {
        if (!available) throw IOException("Native isochronous USB library is unavailable")
        return nativeOpen(fd, endpoint, packetSize)
    }

    fun read(handle: Long, payload: ByteArray, metadata: IntArray, timeoutMs: Int): Int {
        if (!available) throw IOException("Native isochronous USB library is unavailable")
        return nativeRead(handle, payload, metadata, timeoutMs)
    }

    fun close(handle: Long) {
        if (!available) throw IOException("Native isochronous USB library is unavailable")
        nativeClose(handle)
    }

    private external fun nativeOpen(fd: Int, endpoint: Int, packetSize: Int): Long
    private external fun nativeRead(handle: Long, payload: ByteArray, metadata: IntArray, timeoutMs: Int): Int
    private external fun nativeClose(handle: Long)
}
