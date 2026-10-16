package dev.sadking.folio.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeInstallerTest {
    @Test
    fun theEreaderGetsThePhoneAddressOnItsOwnNetwork() {
        val phone = listOf("10.0.0.5", "192.168.43.1", "192.168.1.42")
        assertEquals("192.168.1.42", phoneAddressFor("192.168.1.77", phone))
        // On the phone's hotspot.
        assertEquals("192.168.43.1", phoneAddressFor("192.168.43.120", phone))
        // No shared network: the first address is the best guess.
        assertEquals("10.0.0.5", phoneAddressFor("172.16.0.9", phone))
        assertNull(phoneAddressFor("192.168.1.77", emptyList()))
    }

    @Test
    fun kindleIsTriedFirst() {
        assertEquals("/mnt/us/koreader/plugins", KOREADER_PLUGIN_FOLDERS.first())
    }
}
