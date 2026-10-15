package dev.sadking.folio.link

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.MessageDigest

/**
 * Lets the Folio plugin find the phone on the local network.
 *
 * The plugin broadcasts `FOLIO-DISCOVER <nonce>`. The phone answers `FOLIO <port> <api> <proof>`,
 * where proof is MD5(key + nonce) and key is the login key both sides already share. The plugin
 * trusts an answer only if the proof matches, so another device cannot pose as Folio to collect the
 * key. Nothing secret is sent in either packet.
 */
object LinkDiscovery {
    const val PORT = 8766
    private val request = Regex("^FOLIO-DISCOVER ([0-9a-f]{16,64})$")

    fun answer(packet: String, port: Int, account: KosyncAccount?): String? {
        val nonce = request.find(packet.trim())?.groupValues?.get(1) ?: return null
        val key = account?.key ?: return null
        return "FOLIO $port ${LinkRoutes.API_VERSION} ${proof(key, nonce)}"
    }

    fun proof(key: String, nonce: String) =
        MessageDigest.getInstance("MD5").digest((key + nonce).toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}

/** Answers discovery packets on [LinkDiscovery.PORT] until stopped. Runs on its own thread. */
class LinkDiscoveryResponder(
    private val port: () -> Int?,
    private val account: () -> KosyncAccount?,
    private val listenPort: Int = LinkDiscovery.PORT,
) {
    @Volatile private var socket: DatagramSocket? = null

    /** Binds and starts answering; returns the bound port. */
    fun start(): Int {
        stop()
        val bound =
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(listenPort))
            }
        socket = bound
        Thread({ serve(bound) }, "folio-link-discovery").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun serve(socket: DatagramSocket) {
        val buffer = ByteArray(256)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val listening = port() ?: continue
                val reply = LinkDiscovery.answer(text, listening, account()) ?: continue
                val bytes = reply.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                // A bad packet never stops discovery.
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }
}
