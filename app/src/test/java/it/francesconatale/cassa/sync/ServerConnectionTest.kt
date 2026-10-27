package it.francesconatale.cassa.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConnectionTest {
    @Test
    fun normalizesExpectedLanAddress() {
        assertEquals("http://192.168.178.100:3009", normalizeServerUrl(" http://192.168.178.100:3009/ "))
    }

    @Test
    fun rejectsIncompleteOrPathBasedAddresses() {
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("192.168.178.100:3009") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("http://192.168.178.100:3009/private") }
    }
}
