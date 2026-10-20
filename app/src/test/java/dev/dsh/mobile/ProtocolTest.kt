package dev.dsh.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Cross-language checks against vectors produced by the Node implementation. */
class ProtocolTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val key = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODw==")
    private val salt = Base64.getDecoder().decode("EBESExQVFhcYGRobHB0eHw==")
    private val derived = "245e24d94036fcb2d131393727e53220"

    @Test
    fun hkdfMatchesNode() {
        assertEquals(derived, hex(Tunnel.hkdf(key, salt, "dshm/c2h")))
        assertTrue(hex(Tunnel.hkdf(key, salt, "dshm/h2c")) != derived)
    }

    @Test
    fun sealMatchesNode() {
        val keyBytes = ByteArray(16) { it.toByte() }
        val derivedKey = Tunnel.hkdf(keyBytes, salt, "dshm/c2h")
        val plaintext = "hello world".toByteArray()
        val header = ByteArray(8)
        header[0] = 3
        header[1] = 0; header[2] = 0; header[3] = 0; header[4] = 1
        header[5] = 2
        header[6] = (((plaintext.size + 16) shr 8) and 0xff).toByte()
        header[7] = ((plaintext.size + 16) and 0xff).toByte()
        val sealed = Tunnel.seal(derivedKey, 0, header, plaintext, 0, plaintext.size)
        assertEquals("gq9s7VcBNGblFz2a2nMM5plAEps6vlnp7yzn", Base64.getEncoder().encodeToString(sealed))
    }

    @Test
    fun openRoundTrips() {
        val keyBytes = ByteArray(16) { it.toByte() }
        val derivedKey = Tunnel.hkdf(keyBytes, salt, "dshm/h2c")
        val plaintext = "GET / HTTP/1.1\r\nHost: dsh\r\n\r\n".toByteArray()
        val header = ByteArray(8)
        header[5] = 2
        header[6] = (((plaintext.size + 16) shr 8) and 0xff).toByte()
        header[7] = ((plaintext.size + 16) and 0xff).toByte()
        val sealed = Tunnel.seal(derivedKey, 7, header, plaintext, 0, plaintext.size)
        assertEquals(String(plaintext), String(Tunnel.open(derivedKey, 7, header, sealed)))
    }

    @Test
    fun pairingParsesTheDesktopLink() {
        val link = "dshm://relay?url=relay.example.com%3A9443&room=0123456789abcdef0123456789abcdef&key=" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 7 })
        val config = Pairing.parse(link)
        requireNotNull(config)
        assertEquals("relay.example.com", config.host)
        assertEquals(9443, config.port)
        assertEquals("0123456789abcdef0123456789abcdef", config.room)
        assertEquals(16, config.key.size)
    }

    @Test
    fun pairingRejectsGarbage() {
        assertNull(Pairing.parse("https://example.com"))
        assertNull(Pairing.parse("dshm://relay?url=x&room=short&key=AAAA"))
        assertNull(Pairing.parse(""))
    }
}
