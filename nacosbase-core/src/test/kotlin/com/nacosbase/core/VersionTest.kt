package com.nacosbase.core

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionTest {
    @Test
    fun versionValue_isSemver() {
        assertEquals("0.1.0", Version.VERSION)
    }
}
