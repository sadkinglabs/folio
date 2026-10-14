package dev.sadking.folio.device

import org.junit.Assert.*
import org.junit.Test

class DeviceConfigTest {
    private fun config(mode: String) =
        DeviceConfig(
            "192.168.1.2",
            2222,
            "root",
            "/mnt/us/documents",
            "SHA256:" + "A".repeat(43),
            mode,
        )

    @Test
    fun allThreeModesRequireAHostPin() {
        for (mode in listOf("Key", "Password", "KOReader password-free")) {
            config(mode).validate()
            assertThrows(IllegalArgumentException::class.java) {
                config(mode).copy(fingerprint = "").validate()
            }
        }
    }

    @Test
    fun modeDoesNotChangeDeviceOrBookIdentity() {
        assertEquals(config("Key").id, config("KOReader password-free").id)
        assertEquals(
            config("Password").id,
            config("KOReader password-free").copy(host = "192.168.2.4").id,
        )
    }

    @Test
    fun passwordFreeNeverUsesAnOldSavedSecret() {
        assertEquals(
            "folio-temporary-access",
            config("KOReader password-free").passwordFor(DeviceCredential("old-secret")),
        )
        assertEquals(
            "actual-password",
            config("Password").passwordFor(DeviceCredential("actual-password")),
        )
    }

    @Test
    fun probeEndpointRejectsInvalidAddressAndPort() {
        validateEndpoint("192.168.1.2", 2222)
        validateEndpoint("kindle.local", 2222)
        for (host in
            listOf("", "bad host", "https://kindle", "bad\\host", "bad\u0000host")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            validateEndpoint(host, 2222)
        }
        assertThrows(IllegalArgumentException::class.java) { validateEndpoint("kindle", 0) }
        assertThrows(IllegalArgumentException::class.java) { validateEndpoint("kindle", 65536) }
    }

    @Test
    fun unknownAuthAndTraversalAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { config("None").validate() }
        assertThrows(IllegalArgumentException::class.java) {
            config("KOReader password-free").copy(directory = "/mnt/us/../root").validate()
        }
    }
}
