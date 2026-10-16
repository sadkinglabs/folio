package dev.sadking.folio.link

import dev.sadking.folio.device.linkSetupJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LinkSetupTest {
    @Test
    fun theSetupFileIsVersionTwo() {
        val json = JSONObject(linkSetupJson("http://192.168.1.42:8765", "folio", "0123abcdef"))
        assertEquals("librillo-link-setup", json.getString("format"))
        assertEquals(2, json.getInt("version"))
        assertEquals("http://192.168.1.42:8765", json.getString("server"))
        assertEquals("folio", json.getString("username"))
        assertEquals("0123abcdef", json.getString("userkey"))
        assertEquals("Librillo", json.getString("root"))
        assertEquals(true, json.getBoolean("auto_sync"))
        assertEquals("prompt", json.getString("sync_forward"))
        assertFalse(json.has("device_name"))
        assertEquals(
            setOf(
                "format",
                "version",
                "server",
                "username",
                "userkey",
                "root",
                "auto_sync",
                "sync_forward",
            ),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun aKnownDeviceNameIsIncluded() {
        val json =
            JSONObject(
                linkSetupJson(
                    "http://192.168.43.1:8765",
                    "folio",
                    "0123abcdef",
                    root = "Books",
                    deviceName = "Kindle Paperwhite",
                )
            )
        assertEquals("Kindle Paperwhite", json.getString("device_name"))
        assertEquals("Books", json.getString("root"))
        assertFalse(
            JSONObject(linkSetupJson("http://a:1", "folio", "ab", deviceName = " "))
                .has("device_name")
        )
    }
}
