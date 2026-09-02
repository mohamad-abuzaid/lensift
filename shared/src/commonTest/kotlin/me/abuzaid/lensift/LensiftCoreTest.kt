package me.abuzaid.lensift

import kotlin.test.Test
import kotlin.test.assertEquals

class LensiftCoreTest {
    @Test
    fun exposesBuildIdentity() {
        assertEquals("Lensift shared core", LensiftCore.identity)
    }
}
