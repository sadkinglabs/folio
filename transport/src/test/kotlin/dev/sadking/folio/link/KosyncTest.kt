package dev.sadking.folio.link

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Requests shaped like KOReader's kosync client sends them. */
class KosyncTest {
    private var clock = 1_700_000_000_000L
    private val account = KosyncAccount.fromPassword("folio", "correct horse")
    private val store = MemoryKosyncStore()
    private val kosync = Kosync(store, { account }, { clock })

    private fun auth(user: String = account.username, key: String = account.key) =
        mapOf(
            "Accept" to "application/vnd.koreader.v1+json",
            "x-auth-user" to user,
            "x-auth-key" to key,
        )

    private fun put(body: String, headers: Map<String, String> = auth()) =
        kosync.handle(KosyncRequest("PUT", "/syncs/progress", headers, body, "10.0.0.5"))

    private fun get(document: String, headers: Map<String, String> = auth()) =
        kosync.handle(KosyncRequest("GET", "/syncs/progress/$document", headers, null, "10.0.0.5"))

    private fun progress(
        document: String = "0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7",
        percentage: Double = 0.43,
        device: String = "Kindle",
        deviceId: String = "kindle-1",
        xpointer: String = "/body/DocFragment[12]/body/div/p[4]/text().17",
    ) =
        JSONObject()
            .put("document", document)
            .put("progress", xpointer)
            .put("percentage", percentage)
            .put("device", device)
            .put("device_id", deviceId)
            .toString()

    @Test
    fun keyIsTheMd5OfThePasswordLikeKoreaderSendsIt() {
        assertEquals(
            "5f4dcc3b5aa765d61d8327deb882cf99",
            KosyncAccount.fromPassword("u", "password").key,
        )
    }

    @Test
    fun loginAndRegisterBothWorkWithFoliosAccountOnly() {
        val login = kosync.handle(KosyncRequest("GET", "/users/auth", auth()))
        assertEquals(200, login.status)
        assertEquals("OK", JSONObject(login.body).getString("authorized"))

        val register =
            JSONObject().put("username", account.username).put("password", account.key).toString()
        val created = kosync.handle(KosyncRequest("POST", "/users/create", body = register))
        assertEquals(201, created.status)
        assertEquals("folio", JSONObject(created.body).getString("username"))

        val stranger = JSONObject().put("username", "other").put("password", "x").toString()
        val refused = kosync.handle(KosyncRequest("POST", "/users/create", body = stranger))
        assertEquals(402, refused.status)
        assertEquals(2002, JSONObject(refused.body).getInt("code"))
    }

    @Test
    fun wrongKeyIsUnauthorizedAndRepeatedFailuresAreThrottled() {
        val wrong =
            kosync.handle(KosyncRequest("GET", "/users/auth", auth(key = "nope"), remote = "a"))
        assertEquals(401, wrong.status)
        assertEquals(2001, JSONObject(wrong.body).getInt("code"))
        repeat(Kosync.MAX_FAILURES) {
            kosync.handle(KosyncRequest("GET", "/users/auth", auth(key = "nope"), remote = "b"))
        }
        // Even the right key waits once an address has failed too often...
        assertEquals(
            429,
            kosync.handle(KosyncRequest("GET", "/users/auth", auth(), remote = "b")).status,
        )
        // ...but other addresses are not affected, and the lockout expires.
        assertEquals(
            200,
            kosync.handle(KosyncRequest("GET", "/users/auth", auth(), remote = "c")).status,
        )
        clock += Kosync.FAILURE_WINDOW_MS + 1
        assertEquals(
            200,
            kosync.handle(KosyncRequest("GET", "/users/auth", auth(), remote = "b")).status,
        )
    }

    @Test
    fun everyRouteButHealthAndRegisterNeedsAuth() {
        assertEquals(401, put(progress(), headers = emptyMap()).status)
        assertEquals(401, get("abc", headers = emptyMap()).status)
        assertEquals(200, kosync.handle(KosyncRequest("GET", "/healthcheck")).status)
        assertEquals(404, kosync.handle(KosyncRequest("GET", "/opds", auth())).status)
    }

    @Test
    fun progressRoundTripsWithSecondTimestamps() {
        val saved = put(progress())
        assertEquals(200, saved.status)
        val ack = JSONObject(saved.body)
        assertEquals("0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7", ack.getString("document"))
        assertEquals(clock / 1000, ack.getLong("timestamp"))

        val read = JSONObject(get("0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7").body)
        assertEquals("/body/DocFragment[12]/body/div/p[4]/text().17", read.getString("progress"))
        assertEquals(0.43, read.getDouble("percentage"), 0.0)
        assertEquals("Kindle", read.getString("device"))
        assertEquals("kindle-1", read.getString("device_id"))
        assertEquals(clock / 1000, read.getLong("timestamp"))
    }

    @Test
    fun unknownDocumentIsAnEmptyObject() {
        val read = get("ffffffffffffffffffffffffffffffff")
        assertEquals(200, read.status)
        assertEquals("{}", read.body)
    }

    @Test
    fun theNewestReportWinsButEachDeviceKeepsItsOwn() {
        put(progress(percentage = 0.60, device = "Kobo", deviceId = "kobo-1"))
        clock += 5_000
        put(progress(percentage = 0.20, device = "Folio", deviceId = "phone-1"))
        // KOReader is handed the newest report, even though it is further back; it decides
        // whether to jump, and asks before going backwards.
        assertEquals(
            "phone-1",
            JSONObject(get("0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7").body).getString("device_id"),
        )
        clock += 5_000
        put(progress(percentage = 0.65, device = "Kobo", deviceId = "kobo-1"))
        val read = JSONObject(get("0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7").body)
        assertEquals("kobo-1", read.getString("device_id"))
        assertEquals(0.65, read.getDouble("percentage"), 0.0)
    }

    @Test
    fun malformedReportsAreRejectedWithKoreadersCodes() {
        assertEquals(2003, JSONObject(put("not json").body).getInt("code"))
        assertEquals(2004, JSONObject(put(progress(document = "")).body).getInt("code"))
        assertEquals(403, put(progress(percentage = 1.5)).status)
        assertEquals(403, put(progress(device = "")).status)
        assertEquals(403, put("x".repeat(Kosync.MAX_BODY + 1)).status)
        assertEquals("{}", get("0b4b0f1c2a5d6e7f8091a2b3c4d5e6f7").body)
    }
}
