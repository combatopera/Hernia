package org.ym2149.hernia.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.Serializable
import java.util.stream.Stream
import kotlin.test.assertEquals

class HerniaUtilsTest {
    @Test
    fun `Stream toTypedArray works`() {
        val a: Array<String> = Stream.of("one", "two").toTypedArray()
        assertEquals(Array<String>::class.java, a.javaClass)
        assertArrayEquals(arrayOf("one", "two"), a)
        val b: Array<String?> = Stream.of("one", "two", null).toTypedArray()
        assertEquals(Array<String?>::class.java, b.javaClass)
        assertArrayEquals(arrayOf("one", "two", null), b)
        val c: Array<CharSequence> = Stream.of("x", "y").toTypedArray(CharSequence::class.java)
        assertEquals(Array<CharSequence>::class.java, c.javaClass)
        assertArrayEquals(arrayOf("x", "y"), c)
        val d: Array<CharSequence?> = Stream.of("x", "y", null).toTypedArray(uncheckedCast(CharSequence::class.java))
        assertEquals(Array<CharSequence?>::class.java, d.javaClass)
        assertArrayEquals(arrayOf("x", "y", null), d)
    }

    @Test
    fun `Stream of Pairs toMap works`() {
        val m: Map<Comparable<*>, Serializable> = Stream.of<Pair<Comparable<*>, Serializable>>("x" to "y", 0 to 1, "x" to '2').toMap()
        assertEquals<Map<*, *>>(mapOf("x" to '2', 0 to 1), m)
    }
}
