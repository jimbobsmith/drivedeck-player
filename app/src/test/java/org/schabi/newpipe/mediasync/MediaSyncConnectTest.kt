package org.schabi.newpipe.mediasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/** Same vector as drivedeck-platform services/media-sync/fixtures/connect-code-vector.json (#53). */
class MediaSyncConnectTest {
    private val vector = JSONObject(javaClass.classLoader!!.getResource("media-sync-connect-code-vector.json")!!.readText())
    private fun b(v: String) = MediaSyncConnect.decode(v, 0, 4096)
    private fun failure(block: () -> Unit): String? = try { block(); null } catch (e: SyncFailure) { e.code }
    private fun keys() = JSONObject().put("encryptionKey", MediaSyncConnect.encode(MediaSyncCrypto.bytes(32))).put("routingKey", MediaSyncConnect.encode(MediaSyncCrypto.bytes(32)))
    private fun credential() = JSONObject().put("protocol", 1).put("ownerId", UUID.randomUUID().toString()).put("deviceId", UUID.randomUUID().toString())
        .put("token", MediaSyncConnect.encode(MediaSyncCrypto.bytes(32))).put("serverGeneration", UUID.randomUUID().toString())
    private class Seen { var method = ""; var path = ""; var version: String? = null; var authorization: String? = null; var body = JSONObject() }
    private val address = "http://127.0.0.1:8789"
    // A fake sync service: an OkHttp interceptor answers in place of the network, so the test
    // needs no server (com.sun.net.httpserver is not on Android's unit-test classpath).
    private fun service(reply: (JSONObject) -> Pair<Int, JSONObject>): Pair<OkHttpClient, Seen> {
        val seen = Seen()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen.method = request.method; seen.path = request.url.encodedPath
            seen.version = request.header("x-dd-sync-version"); seen.authorization = request.header("Authorization")
            seen.body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            val (status, value) = reply(seen.body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fake")
                .body(value.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return client to seen
    }

    @Test fun vectorDerivesAndUnwrapsExactlyLikeThePlatform() {
        val bytes = ByteArray(16) { ((it * 17 + 3) and 255).toByte() }
        for (typed in listOf(vector.getString("code"), vector.getString("codeLowercaseSpaced"), vector.getString("code").replace('1', 'l').replace('0', 'o'))) {
            assertArrayEquals(bytes, MediaSyncConnect.parseCode(typed))
            val d = MediaSyncConnect.derive(typed)
            assertEquals(vector.getString("lookupId"), d.lookupId); assertEquals(vector.getString("proof"), d.proof)
            assertEquals(vector.getString("wrapKey"), MediaSyncConnect.encode(d.wrapKey))
        }
        val keys = vector.getJSONObject("keys"); val wrapKey = b(vector.getString("wrapKey"))
        assertEquals(vector.getString("wrappedKeys"), MediaSyncConnect.wrapKeys(keys, wrapKey, b(vector.getString("nonce"))))
        val unwrapped = MediaSyncConnect.unwrapKeys(vector.getString("wrappedKeys"), wrapKey)
        assertEquals(keys.getString("encryptionKey"), unwrapped.getString("encryptionKey")); assertEquals(keys.getString("routingKey"), unwrapped.getString("routingKey"))
    }
    @Test fun nonCanonicalCodesTamperingAndBadEncodingFailClosed() {
        val code = vector.getString("code")
        assertEquals("invalid_code", failure { MediaSyncConnect.parseCode(code.dropLast(1) + "9") })
        assertEquals("invalid_code", failure { MediaSyncConnect.parseCode(code.dropLast(1)) })
        assertEquals("invalid_code", failure { MediaSyncConnect.parseCode(code.dropLast(1) + "U") })
        val tampered = b(vector.getString("wrappedKeys")).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertEquals("authentication_failed", failure { MediaSyncConnect.unwrapKeys(MediaSyncConnect.encode(tampered), b(vector.getString("wrapKey"))) })
        assertEquals("invalid_encoding", failure { MediaSyncConnect.decode(vector.getString("nonce") + "=", 12, 12) })
        for (n in 1..40) { val raw = MediaSyncCrypto.bytes(n); assertArrayEquals(raw, MediaSyncConnect.decode(MediaSyncConnect.encode(raw), 0, 64)) }
    }
    @Test fun serviceAddressMustBeHttpsOrLoopbackForTests() {
        assertEquals("https://sync.example.test", MediaSyncConnect.serviceOrigin(" https://sync.example.test/ "))
        assertEquals("http://127.0.0.1:8789", MediaSyncConnect.serviceOrigin("http://127.0.0.1:8789"))
        assertEquals("verified_transport_required", failure { MediaSyncConnect.serviceOrigin("http://192.168.1.10:8789") })
        for (bad in listOf("https://sync.example.test/portal", "https://u:p@sync.example.test", "sync.example.test", "https://sync.example.test/?x=1"))
            assertEquals(bad, "invalid_service_address", failure { MediaSyncConnect.serviceOrigin(bad) })
        assertEquals("invalid_device_label", failure { MediaSyncConnect.validLabel("  ") })
        assertEquals("invalid_device_label", failure { MediaSyncConnect.validLabel("x".repeat(97)) })
    }
    @Test fun signInSendsOnlyLookupProofAndLabelAndReturnsEnrollmentConfig() {
        val keys = keys(); val credential = credential(); val d = MediaSyncConnect.derive(vector.getString("code"))
        val (http, seen) = service { body ->
            if (body.getString("lookupId") == d.lookupId && body.getString("proof") == d.proof)
                200 to JSONObject().put("protocol", 1).put("credential", credential).put("wrappedKeys", MediaSyncConnect.wrapKeys(keys, d.wrapKey, ByteArray(12) { 7 }))
            else 403 to JSONObject().put("protocol", 1).put("code", "invalid_connect_code")
        }
        val config = MediaSyncConnect.connect("$address/", vector.getString("codeLowercaseSpaced"), " DD PipePipe on phone ", http)
        assertEquals(MediaSyncCrypto.SCHEMA, config.getString("schema")); assertEquals("$address/", config.getString("endpoint"))
        assertEquals(credential.toString(), config.getJSONObject("credential").toString())
        assertEquals(keys.getString("encryptionKey"), config.getJSONObject("keys").getString("encryptionKey"))
        assertEquals("POST", seen.method); assertEquals("/v1/connect", seen.path); assertEquals("1", seen.version); assertNull(seen.authorization)
        assertEquals(setOf("protocol", "lookupId", "proof", "label"), seen.body.keys().asSequence().toSet())
        assertEquals("DD PipePipe on phone", seen.body.getString("label"))
        assertFalse(seen.body.toString().contains(MediaSyncConnect.encode(d.wrapKey)))
    }
    @Test fun refusalsKeepSanitizedCodesAndWrongWrapsOrShapesAreRejected() {
        val code = vector.getString("code")
        val (refused, _) = service { 403 to JSONObject().put("protocol", 1).put("code", "invalid_connect_code") }
        assertEquals("invalid_connect_code", failure { MediaSyncConnect.connect(address, code, "Phone", refused) })
        val (odd, _) = service { 403 to JSONObject().put("protocol", 1).put("code", "<script>") }
        assertEquals("connect_failed", failure { MediaSyncConnect.connect(address, code, "Phone", odd) })
        val other = MediaSyncConnect.derive("0000-0000-0000-0000-0000-0000-00")
        val (wrong, _) = service { 200 to JSONObject().put("protocol", 1).put("credential", credential()).put("wrappedKeys", MediaSyncConnect.wrapKeys(keys(), other.wrapKey, ByteArray(12))) }
        assertEquals("authentication_failed", failure { MediaSyncConnect.connect(address, code, "Phone", wrong) })
        val d = MediaSyncConnect.derive(code)
        val (extra, _) = service { 200 to JSONObject().put("protocol", 1).put("credential", credential()).put("wrappedKeys", MediaSyncConnect.wrapKeys(keys(), d.wrapKey, ByteArray(12))).put("keys", keys()) }
        assertEquals("invalid_response", failure { MediaSyncConnect.connect(address, code, "Phone", extra) })
        val (badCredential, _) = service { 200 to JSONObject().put("protocol", 1).put("credential", credential().put("token", "short")).put("wrappedKeys", MediaSyncConnect.wrapKeys(keys(), d.wrapKey, ByteArray(12))) }
        assertEquals("invalid_response", failure { MediaSyncConnect.connect(address, code, "Phone", badCredential) })
        assertEquals("offline", failure { MediaSyncConnect.connect("http://127.0.0.1:1", code, "Phone") })
        try { MediaSyncConnect.connect(address, code, "", refused); fail() } catch (e: SyncFailure) { assertTrue(e.code == "invalid_device_label") }
    }
}
