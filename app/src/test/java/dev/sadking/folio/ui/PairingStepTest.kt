package dev.sadking.folio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStepTest {
    private val pin = "SHA256:" + "A".repeat(43)

    @Test
    fun theFirstMissingDetailDecidesTheStep() {
        assertEquals(PairingStep.NETWORK, pairingStep("", 2222, "", "Key", false, false))
        assertEquals(PairingStep.NETWORK, pairingStep("192.168.1.2", 0, pin, "Key", true, false))
        assertEquals(PairingStep.ADDRESS, pairingStep("192.168.1.2", 2222, "", "Key", true, false))
        assertEquals(
            PairingStep.ADDRESS,
            pairingStep("192.168.1.2", 2222, "not-a-fingerprint", "Key", true, false),
        )
        assertEquals(PairingStep.LOGIN, pairingStep("192.168.1.2", 2222, pin, "Key", false, false))
        assertEquals(
            PairingStep.LOGIN,
            pairingStep("192.168.1.2", 2222, pin, "Password", false, false),
        )
        assertEquals(PairingStep.FOLDER, pairingStep("192.168.1.2", 2222, pin, "Key", true, false))
    }

    @Test
    fun passwordFreeAndASavedSecretNeedNoLoginDetails() {
        assertEquals(
            PairingStep.FOLDER,
            pairingStep("192.168.1.2", 2222, pin, "KOReader password-free", false, false),
        )
        assertEquals(
            PairingStep.FOLDER,
            pairingStep("192.168.1.2", 2222, pin, "Password", false, true),
        )
    }
}
