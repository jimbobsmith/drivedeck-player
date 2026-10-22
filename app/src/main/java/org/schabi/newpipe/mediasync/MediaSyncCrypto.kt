package org.schabi.newpipe.mediasync

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Interoperable platform issue 27 envelope. No provider credentials, URLs or player commands. */
object MediaSyncCrypto {
    val libraryKinds = setOf("playlist", "watch-later", "history")
    const val SCHEMA = "drivedeck.media-sync.enrollment.v1"
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val random = SecureRandom()
    fun check(ok: Boolean, code: String = "invalid_sync_data") { if (!ok) throw SyncFailure(code) }
    fun exact(o: JSONObject, vararg fields: String) = check(o.keys().asSequence().toSet() == fields.toSet())
    fun uuid(v: Any?) = v is String && uuidPattern.matches(v)
    fun number(o: JSONObject, key: String): Long {
        val v = o.get(key); check(v is Number)
        val n = (v as Number).toLong(); check(n >= 0 && n <= 9007199254740991L && v.toDouble() == n.toDouble())
        return n
    }
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun decode(value: String, min: Int, max: Int = min): ByteArray {
        check(value.matches(Regex("[A-Za-z0-9_-]+")))
        val result = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        check(result.size in min..max && encode(result) == value); return result
    }
    fun bytes(size: Int) = ByteArray(size).also { random.nextBytes(it) }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun fingerprint(keys: JSONObject) = sha(decode(keys.getString("encryptionKey"), 32) + decode(keys.getString("routingKey"), 32))
    fun validatePayload(p: JSONObject): JSONObject {
        val kind = p.getString("kind")
        if (kind == "profile-member") {
            exact(p, "kind", "provider", "id", "version", "profileId", "channelId")
            check(p.get("version") == 3 && p.getString("provider") == "youtube", "unsupported_profiles_version")
            check(ProfileRecordRules.validMembership(p.getString("id"), p.getString("profileId"), p.getString("channelId")), "invalid_profile_identity")
            return p
        }
        if (kind == "profile") {
            exact(p, "kind", "provider", "id", "version", "name", "bgColor", "textColor")
            check(p.get("version") == 3 && p.getString("provider") == "youtube", "unsupported_profiles_version")
            check(uuid(p.get("id")), "invalid_profile_identity")
            check(p.get("name") is String, "profile_not_portable")
            val name = p.getString("name")
            check(ProfileRecordRules.validName(name), "profile_not_portable")
            for (color in listOf("bgColor", "textColor")) check(ProfileRecordRules.validColor(p.getString(color)), "profile_not_portable")
            return p
        }
        if (kind in libraryKinds) {
            if (kind == "history") exact(p, "kind", "provider", "id", "version", "watchedAtMs")
            else if (kind == "playlist") exact(p, "kind", "provider", "id", "version", "name", "items")
            else exact(p, "kind", "provider", "id", "version", "items")
            check(p.get("version") == 2 && p.getString("provider") == "youtube", "unsupported_library_version")
            if (kind == "history") check(p.getString("id").matches(Regex("[A-Za-z0-9_-]{11}")) && number(p, "watchedAtMs") in 1..8640000000000000L, "invalid_history")
            else {
                check(if (kind == "playlist") uuid(p.get("id")) else p.getString("id") == "watch-later", "invalid_playlist_identity")
                if (kind == "playlist") { val name = p.getString("name"); check(name.isNotBlank() && name.toByteArray(UTF_8).size <= 160 && name.none { it.code < 32 || it.code == 127 }, "invalid_playlist_name") }
                val items = p.getJSONArray("items"); check(items.length() <= 200, "playlist_not_portable")
                for (i in 0 until items.length()) check(items.get(i) is String && items.getString(i).matches(Regex("[A-Za-z0-9_-]{11}")), "playlist_not_portable")
            }
            return p
        }
        if (kind == "subscription") exact(p, "kind", "provider", "id")
        else exact(p, "kind", "provider", "id", "positionMs", "sessionId", "checkpointSeq")
        check(p.getString("provider") == "youtube" && kind in listOf("subscription", "resume"))
        check(p.getString("id").matches(if (kind == "subscription") Regex("UC[A-Za-z0-9_-]{22}") else Regex("[A-Za-z0-9_-]{11}")))
        if (kind == "resume") { check(number(p, "positionMs") <= 31536000000L && uuid(p.get("sessionId"))); number(p, "checkpointSeq") }
        return p
    }
    fun recordKey(keys: JSONObject, payload: JSONObject): String {
        validatePayload(payload)
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(decode(keys.getString("routingKey"), 32), "HmacSHA256"))
        val identity = JSONArray().put("youtube").put(payload.getString("kind")).put(payload.getString("id")).toString()
        return mac.doFinal(identity.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun aad(owner: String, m: JSONObject): ByteArray = JSONArray().put("drivedeck.media-sync.v1").put(owner)
        .put(m.getString("mutationId")).put(m.getString("recordKey")).put(m.getString("kind")).put(m.getString("operation"))
        .put(number(m, "baseRevision")).put(number(m, "resumeEpoch")).put(m.getJSONObject("envelope").getInt("keyEpoch")).toString().toByteArray(UTF_8)
    fun validateMutation(m: JSONObject) {
        exact(m, "protocol", "serverGeneration", "mutationId", "recordKey", "kind", "operation", "baseRevision", "resumeEpoch", "envelope")
        check(m.get("protocol") == 1 && uuid(m.get("serverGeneration")) && uuid(m.get("mutationId")))
        check(m.getString("recordKey").matches(Regex("[a-f0-9]{64}")) && m.getString("kind") in (listOf("subscription", "resume", "profile", "profile-member") + libraryKinds) && m.getString("operation") in listOf("put", "remove"))
        check(m.getString("kind") != "watch-later" || m.getString("operation") == "put", "watch_later_clear_with_empty_list")
        number(m, "baseRevision"); number(m, "resumeEpoch")
        val e = m.getJSONObject("envelope"); exact(e, "keyEpoch", "nonce", "ciphertext"); check(e.get("keyEpoch") == 1)
        decode(e.getString("nonce"), 12); decode(e.getString("ciphertext"), 16, 4096)
    }
    fun seal(keys: JSONObject, owner: String, header: JSONObject, payload: JSONObject): JSONObject {
        validatePayload(payload); check(uuid(owner))
        val m = JSONObject(header.toString()).put("protocol", 1).put("recordKey", recordKey(keys, payload)).put("kind", payload.getString("kind"))
        val nonce = bytes(12); m.put("envelope", JSONObject().put("keyEpoch", 1).put("nonce", encode(nonce)))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(decode(keys.getString("encryptionKey"), 32), "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad(owner, m))
        m.getJSONObject("envelope").put("ciphertext", encode(cipher.doFinal(payload.toString().toByteArray(UTF_8)))); validateMutation(m); return m
    }
    fun open(keys: JSONObject, owner: String, m: JSONObject): JSONObject {
        validateMutation(m); val e = m.getJSONObject("envelope")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode(keys.getString("encryptionKey"), 32), "AES"), GCMParameterSpec(128, decode(e.getString("nonce"), 12))); cipher.updateAAD(aad(owner, m))
        val p = validatePayload(JSONObject(String(cipher.doFinal(decode(e.getString("ciphertext"), 16, 4096)), UTF_8)))
        check(p.getString("kind") == m.getString("kind") && recordKey(keys, p) == m.getString("recordKey")); return p
    }
    fun validateConfig(c: JSONObject): JSONObject {
        exact(c, "schema", "endpoint", "credential", "keys"); check(c.getString("schema") == SCHEMA)
        val u = URI(c.getString("endpoint")); check(u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && (u.rawPath.isNullOrEmpty() || u.rawPath == "/"))
        check(u.host != null && (u.scheme == "https" || (u.scheme == "http" && u.host == "127.0.0.1")), "verified_transport_required")
        val a = c.getJSONObject("credential"); exact(a, "protocol", "ownerId", "deviceId", "token", "serverGeneration")
        check(a.get("protocol") == 1 && uuid(a.get("ownerId")) && uuid(a.get("deviceId")) && uuid(a.get("serverGeneration"))); decode(a.getString("token"), 32)
        val keys = c.getJSONObject("keys"); exact(keys, "encryptionKey", "routingKey"); fingerprint(keys); return c
    }
    fun unprotect(e: JSONObject, passphrase: String): JSONObject {
        exact(e, "schema", "kdf", "iterations", "salt", "nonce", "ciphertext")
        check(e.getString("schema") == SCHEMA && e.getString("kdf") == "PBKDF2-SHA256" && e.get("iterations") == 600000)
        check(passphrase.matches(Regex("[\\x20-\\x7e]{12,128}")), "invalid_passphrase")
        val spec = PBEKeySpec(passphrase.toCharArray(), decode(e.getString("salt"), 16), 600000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decode(e.getString("nonce"), 12))); cipher.updateAAD(SCHEMA.toByteArray(UTF_8))
            return validateConfig(JSONObject(String(cipher.doFinal(decode(e.getString("ciphertext"), 16, 8192)), UTF_8)))
        } finally { key.fill(0) }
    }
}
class SyncFailure(val code: String): Exception(code)
