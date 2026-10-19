package org.schabi.newpipe.mediasync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Non-exportable Android Keystore key; enrollment ciphertext is excluded from app backups. */
class MediaSyncSecrets(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "dd-media-sync.secret"))
    private val alias = "drivedeck.media-sync.v1"
    fun exists() = file.baseFile.exists()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        MediaSyncCrypto.check(create, "secret_unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    fun save(config: JSONObject) {
        MediaSyncCrypto.check(!exists(), "disconnect_before_enrollment"); MediaSyncCrypto.validateConfig(config)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val value = JSONObject().put("nonce", MediaSyncCrypto.encode(cipher.iv)).put("ciphertext", MediaSyncCrypto.encode(cipher.doFinal(config.toString().toByteArray(Charsets.UTF_8))))
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun load(): JSONObject {
        MediaSyncCrypto.check(file.baseFile.length() <= 32768, "invalid_enrollment")
        val value = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, MediaSyncCrypto.decode(value.getString("nonce"), 12)))
        return MediaSyncCrypto.validateConfig(JSONObject(String(cipher.doFinal(MediaSyncCrypto.decode(value.getString("ciphertext"), 16, 16384)), Charsets.UTF_8)))
    }
    fun delete() { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
