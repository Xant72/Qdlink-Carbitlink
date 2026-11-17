package io.github.queryparams.overtakelink.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

class AppMessagesTest {
    private fun roundTrip(vararg data: String): AppMessages.Message? {
        val b = AppMessages.create("QDRIVE_ASSISTANT", "HUINFO", *data).toByteArray(StandardCharsets.UTF_8)
        return AppMessages.parse(b, 0, b.size)
    }

    @Test
    fun `ascii message round-trips`() {
        val m = roundTrip("{\"D\":0,\"T\":\"s\",\"V\":\"Geely\"}")
        assertNotNull(m)
        assertEquals("QDRIVE_ASSISTANT", m!!.appId)
        assertEquals("HUINFO", m.logicId)
        assertEquals(listOf("{\"D\":0,\"T\":\"s\",\"V\":\"Geely\"}"), m.data)
    }

    @Test
    fun `lengths are utf-8 bytes, so cyrillic and chinese data still parse`() {
        // The head unit counts field lengths in bytes; a Java String index is not a byte offset.
        val m = roundTrip("{\"V\":\"Джили Атлас\"}", "吉利")
        assertNotNull(m)
        assertEquals(listOf("{\"V\":\"Джили Атлас\"}", "吉利"), m!!.data)
    }

    @Test
    fun `a broken crc is rejected`() {
        val b = AppMessages.create("A", "B", "x").toByteArray(StandardCharsets.UTF_8)
        b[b.size - 1] = if (b[b.size - 1] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        assertNull(AppMessages.parse(b, 0, b.size))
    }
}
