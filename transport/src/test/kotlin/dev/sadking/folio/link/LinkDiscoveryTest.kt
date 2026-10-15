package dev.sadking.folio.link

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class LinkDiscoveryTest {
    private val account = KosyncAccount.fromPassword("folio", "secret")

    @Test
    fun answersOnlyWellFormedRequestsWithAProofOfTheKey() {
        val nonce = "0123456789abcdef"
        val reply = LinkDiscovery.answer("FOLIO-DISCOVER $nonce", 8765, account)!!
        val parts = reply.split(" ")
        assertEquals(listOf("FOLIO", "8765", "${LinkRoutes.API_VERSION}"), parts.take(3))
        assertEquals(LinkDiscovery.proof(account.key, nonce), parts[3])
        // The key itself never travels.
        assertFalse(reply.contains(account.key))
        assertNull(LinkDiscovery.answer("FOLIO-DISCOVER short", 8765, account))
        assertNull(LinkDiscovery.answer("HELLO", 8765, account))
        assertNull(LinkDiscovery.answer("FOLIO-DISCOVER $nonce", 8765, null))
        assertNotEquals(parts[3], LinkDiscovery.proof(account.key, "fedcba9876543210"))
    }

    @Test
    fun theResponderAnswersOverUdp() {
        val responder = LinkDiscoveryResponder({ 8765 }, { account }, listenPort = 0)
        val port = responder.start()
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 3000
                val ask = "FOLIO-DISCOVER 00112233445566778899aabbccddeeff".toByteArray()
                client.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), port))
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                client.receive(packet)
                val reply = String(packet.data, 0, packet.length)
                assertTrue(reply, reply.startsWith("FOLIO 8765 "))
                // Garbage gets no answer, and does not stop the responder.
                val junk = "nonsense".toByteArray()
                client.send(DatagramPacket(junk, junk.size, InetAddress.getLoopbackAddress(), port))
                client.soTimeout = 500
                assertThrows(SocketTimeoutException::class.java) {
                    client.receive(DatagramPacket(buffer, buffer.size))
                }
                client.soTimeout = 3000
                client.send(DatagramPacket(ask, ask.size, InetAddress.getLoopbackAddress(), port))
                client.receive(DatagramPacket(buffer, buffer.size))
            }
        } finally {
            responder.stop()
        }
    }
}
