package com.technicallyvu.scope.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameSizeLimitsTest {
    @Test
    fun `supported endoscopes and UHD frames fit the decoded allocation limit`() {
        assertTrue(FrameSizeLimits.supports(320, 240))
        assertTrue(FrameSizeLimits.supports(640, 480))
        assertTrue(FrameSizeLimits.supports(3840, 2160))
    }

    @Test
    fun `oversized or invalid JPEG dimensions are rejected without integer overflow`() {
        assertFalse(FrameSizeLimits.supports(4096, 4096))
        assertFalse(FrameSizeLimits.supports(65_535, 65_535))
        assertFalse(FrameSizeLimits.supports(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(FrameSizeLimits.supports(0, 240))
        assertFalse(FrameSizeLimits.supports(320, -1))
    }
}
