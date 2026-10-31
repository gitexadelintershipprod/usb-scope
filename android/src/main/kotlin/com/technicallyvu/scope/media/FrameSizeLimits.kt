package com.technicallyvu.scope.media

/** Limits decoded allocations independently of the compressed byte count reported by a camera. */
internal object FrameSizeLimits {
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 8_388_608L

    fun supports(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_PIXELS
}
