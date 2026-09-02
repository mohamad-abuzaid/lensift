package me.abuzaid.lensift.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradientFixtureResourceTest {
    @Test
    fun parsesTheDeterministic32By32GradientFixture() {
        val classLoader = assertNotNull(javaClass.classLoader)
        val stream = assertNotNull(classLoader.getResourceAsStream("phash/gradient-32x32.luma"))
        val values = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().trim().split(Regex("\\s+")).map(String::toInt)
        }

        assertEquals(32 * 32, values.size)
        assertTrue(values.all { it in 0..255 })
        assertEquals(List(32) { it * 255 / 31 }, values.take(32))
    }
}
