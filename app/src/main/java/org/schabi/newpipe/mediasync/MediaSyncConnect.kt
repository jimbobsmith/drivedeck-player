package org.schabi.newpipe.mediasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sign in with a one-time code from the service portal (drivedeck-platform #53). Byte-compatible
 * with services/media-sync/web/connect.mjs there; the shared vector in the unit tests pins it.
 * Only the lookup ID, proof and device name are sent; the wrap key and library keys stay here.
 * Plain JVM code (own base64url, no android.util) so the vector runs as a local unit test.
 */
object MediaSyncConnect {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val WRAP_AAD = "media-sync.v1/key-wrap/1"
    class Derived(val lookupId: String, val proof: String, val wrapKey: ByteArray)
    private fun fail(code: String): Nothing = throw SyncFailure(code)

    /** Canonical Crockford base32 only: case, spaces and dashes ignored, I/L read as 1 and O as 0. */
    fun parseCode(text: String): ByteArray {
        val clean = text.uppercase().replace(Regex("[\\s-]"), "").replace(Regex("[IL]"), "1").replace("O", "0")
        if (clean.length != 26 || clean.any { it !in ALPHABET }) fail("invalid_code")
        var bits = 0; var value = 0; val out = ByteArrayOutputStream()
        for (c in clean) {
            value = ((value shl 5) or ALPHABET.indexOf(c)) and 0xfff; bits += 5
            if (bits >= 8) { out.write((value ushr (bits - 8)) and 255); bits -= 8 }
        }
        if (value and ((1 shl bits) - 1) != 0) fail("invalid_code")
        return out.toByteArray()
    }
    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    /** RFC 5869 HKDF-SHA-256 with an empty salt. */
    fun hkdf(secret: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(ByteArray(32), secret); val out = ByteArrayOutputStream(); var block = ByteArray(0); var i = 1
        while (out.size() < length) { block = hmac(prk, block + "media-sync.v1/$info".toByteArray(UTF_8) + byteArrayOf(i.toByte())); out.write(block); i++ }
        return out.toByteArray().copyOf(length)
    }
    fun derive(code: String): Derived {
        val secret = parseCode(code)
        return Derived(encode(hkdf(secret, "lookup", 16)), encode(hkdf(secret, "proof", 32)), hkdf(secret, "wrap", 32))
    }
    fun unwrapKeys(wrapped: String, wrapKey: ByteArray): JSONObject {
        val bytes = decode(wrapped, 12 + 16 + 1, 512)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); cipher.updateAAD(WRAP_AAD.toByteArray(UTF_8))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        } catch (_: AEADBadTagException) { fail("authentication_failed") }
        val keys = JSONObject(String(plain, UTF_8))
        if (keys.keys().asSequence().toSet() != setOf("encryptionKey", "routingKey")) fail("invalid_keys")
        decode(keys.getString("encryptionKey"), 32, 32); decode(keys.getString("routingKey"), 32, 32)
        return keys
    }
    /** Mirrors the portal; used by tests to build service answers. */
    fun wrapKeys(keys: JSONObject, wrapKey: ByteArray, nonce: ByteArray): String {
        val plain = "{\"encryptionKey\":\"${keys.getString("encryptionKey")}\",\"routingKey\":\"${keys.getString("routingKey")}\"}"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(WRAP_AAD.toByteArray(UTF_8))
        return encode(nonce + cipher.doFinal(plain.toByteArray(UTF_8)))
    }
    /** HTTPS origin, or literal 127.0.0.1 HTTP for local tests (the same rule as validateConfig). */
    fun serviceOrigin(text: String): String {
        val u = try { URI(text.trim()) } catch (_: Exception) { fail("invalid_service_address") }
        if (u.host == null || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null || !(u.rawPath.isNullOrEmpty() || u.rawPath == "/")) fail("invalid_service_address")
        if (!(u.scheme == "https" || (u.scheme == "http" && u.host == "127.0.0.1"))) fail("verified_transport_required")
        return u.scheme + "://" + u.rawAuthority
    }
    fun validLabel(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(UTF_8).size > 96 || trimmed.any { it.code < 32 }) fail("invalid_device_label")
        return trimmed
    }
    /** Redeems the code and returns the same config an enrollment file holds. */
    fun connect(address: String, code: String, label: String, http: OkHttpClient = client): JSONObject {
        val origin = serviceOrigin(address); val name = validLabel(label); val d = derive(code)
        val body = JSONObject().put("protocol", 1).put("lookupId", d.lookupId).put("proof", d.proof).put("label", name)
        val request = Request.Builder().url("$origin/v1/connect").header("x-dd-sync-version", "1").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val (status, value) = try {
            http.newCall(request).execute().use { response ->
                if (response.code in 300..399) fail("redirect_rejected")
                val bytes = response.body?.bytes() ?: fail("invalid_response")
                if (bytes.size > 16384) fail("invalid_response")
                response.code to (try { JSONObject(String(bytes, UTF_8)) } catch (_: Exception) { fail("invalid_response") })
            }
        } catch (_: IOException) { fail("offline") }
        if (status != 200) fail(value.optString("code").takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "connect_failed")
        if (value.keys().asSequence().toSet() != setOf("protocol", "credential", "wrappedKeys") || value.opt("protocol") != 1) fail("invalid_response")
        val keys = unwrapKeys(value.getString("wrappedKeys"), d.wrapKey)
        val credential = value.optJSONObject("credential") ?: fail("invalid_response")
        val valid = try {
            credential.keys().asSequence().toSet() == setOf("protocol", "ownerId", "deviceId", "token", "serverGeneration") && credential.opt("protocol") == 1 &&
                listOf("ownerId", "deviceId", "serverGeneration").all { MediaSyncCrypto.uuid(credential.opt(it)) } && decode(credential.getString("token"), 32, 32).size == 32
        } catch (_: Exception) { false }
        if (!valid) fail("invalid_response")
        // MediaSyncSecrets.save runs the full MediaSyncCrypto.validateConfig before anything is stored.
        return JSONObject().put("schema", MediaSyncCrypto.SCHEMA).put("endpoint", "$origin/").put("credential", credential).put("keys", keys)
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(); var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i); var v = 0
            for (j in 0 until 3) v = (v shl 8) or (if (j < n) bytes[i + j].toInt() and 255 else 0)
            for (j in 0..n) out.append(B64[(v ushr (18 - 6 * j)) and 63])
            i += 3
        }
        return out.toString()
    }
    fun decode(value: String, min: Int, max: Int): ByteArray {
        if (!value.matches(Regex("[A-Za-z0-9_-]+")) || value.length % 4 == 1) fail("invalid_encoding")
        val out = ByteArrayOutputStream(); var v = 0; var bits = 0
        for (c in value) { v = (v shl 6) or B64.indexOf(c); bits += 6; if (bits >= 8) { out.write((v ushr (bits - 8)) and 255); bits -= 8; v = v and ((1 shl bits) - 1) } }
        val bytes = out.toByteArray()
        if (bytes.size !in min..max || encode(bytes) != value) fail("invalid_encoding")
        return bytes
    }
}
