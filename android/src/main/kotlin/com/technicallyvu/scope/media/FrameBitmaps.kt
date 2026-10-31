package com.technicallyvu.scope.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.image.Sharpener
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.image.YuyvConverter

/** Converts frames to bitmaps and applies the view transform. Reuses its pixel buffer; use from one thread. */
class FrameBitmaps(private val onUnsupportedSize: (width: Int, height: Int) -> Unit = { _, _ -> }) {
    private var argb = IntArray(0)
    private var jpegPixels = IntArray(0)

    /**
     * [argbDenoiser] and [argbSharpener] apply to decoded JPEG frames only, in that order (the same
     * order `ScopeSession` uses for YUYV frames, which reach this class already filtered).
     */
    fun toBitmap(
        data: FrameData,
        argbDenoiser: TemporalDenoiser? = null,
        argbSharpener: Sharpener? = null,
    ): Bitmap? = when (data) {
        is FrameData.Jpeg -> {
            // JPEG dimensions are supplied by the USB device. Check them before the platform
            // decoder allocates pixels; a small compressed frame can otherwise exhaust the heap.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, bounds)
            if (!supportedSize(bounds.outWidth, bounds.outHeight)) null else {
                // Mutable only when a filter needs to write pixels back into it; an immutable
                // decode is cheaper and is what the rest of the unfiltered path assumes.
                val filtering = argbDenoiser != null || argbSharpener != null
                val opts = BitmapFactory.Options().apply {
                    inMutable = filtering
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, opts)
                if (bmp != null && filtering) {
                    val n = bmp.width * bmp.height
                    if (jpegPixels.size < n) jpegPixels = IntArray(n)
                    bmp.getPixels(jpegPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    argbDenoiser?.applyArgb(jpegPixels, bmp.width, bmp.height)
                    argbSharpener?.applyArgb(jpegPixels, bmp.width, bmp.height)
                    bmp.setPixels(jpegPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                }
                bmp
            }
        }
        is FrameData.Yuyv422 -> {
            if (!supportedSize(data.width, data.height)) null else {
                val n = data.width * data.height
                if (argb.size < n) argb = IntArray(n)
                YuyvConverter.toArgb(data, argb)
                Bitmap.createBitmap(argb, data.width, data.height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private fun supportedSize(width: Int, height: Int): Boolean {
        if (FrameSizeLimits.supports(width, height)) return true
        // Negative bounds mean an undecodable JPEG, not an oversized image.
        if (width > 0 && height > 0) onUnsupportedSize(width, height)
        return false
    }

    /** Mirror horizontally (if asked), then rotate clockwise. Returns [src] itself when nothing changes. */
    fun transform(src: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0 && !mirror) return src
        val m = Matrix()
        if (mirror) m.preScale(-1f, 1f)
        m.postRotate(rot.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
