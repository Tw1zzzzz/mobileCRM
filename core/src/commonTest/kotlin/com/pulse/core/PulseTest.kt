package com.pulse.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PulseTest {
    @Test
    fun versionMatchesIos() {
        assertEquals("1.0.0", Pulse.VERSION)
    }
}
