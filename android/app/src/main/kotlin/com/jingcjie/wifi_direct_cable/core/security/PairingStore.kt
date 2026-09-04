package com.jingcjie.wifi_direct_cable.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jingcjie.wifi_direct_cable.core.model.DeviceId
import com.jingcjie.wifi_direct_cable.diagnostics.DiagnosticsLogger
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent device identity and pairing records.
 *
 * Long-term pairing secrets are encrypted with an AES-256-GCM key held in the
 * **Android Keystore**, so the key material itself never enters the app's process
 * memory and cannot be lifted out of a backup or an extracted `shared_prefs`
 * file. Only the wrapped ciphertext is written to preferences.
 *
 * Implemented directly against the platform Keystore rather than
 * `androidx.security:security-crypto`, to avoid adding a dependency that could
 * not be verified to resolve in this environment. The trade-off is a little more
 * code here; the security properties are the same.
 *
 * The Keystore key is created without `setUserAuthenticationRequired`, because the
 * gateway must be able to re-establish a session while the phone is locked in a
 * pocket. That is a deliberate availability-over-strictness choice and is recorded
 * in `docs/ANDROID_LIMITATIONS.md`.
 */
class PairingStore(context: Context) : PairingRepository {

    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * This installation's stable device ID, created on first access.
     */
    @Synchronized
    override fun localDeviceId(): DeviceId {
        DeviceId.parseOrNull(preferences.getString(KEY_DEVICE_ID, null))?.let { return it }
        val generated = DeviceId.random()
        preferences.edit().putString(KEY_DEVICE_ID, generated.toString()).apply()
        DiagnosticsLogger.log(
            "security",
            "Generated local device identity",
            mapOf("deviceId" to generated.shortLabel())
        )
        return generated
    }

    /** Display name this device advertises to peers. Not an identifier. */
    override fun localDisplayName(): String =
        preferences.getString(KEY_DISPLAY_NAME, null) ?: android.os.Build.MODEL ?: "Android device"

    fun setLocalDisplayName(name: String) {
        preferences.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    // ------------------------------------------------------------- pairings --

    @Synchronized
    override fun findPairing(peerDeviceId: DeviceId): PairingRecord? {
        val stored = preferences.getString(pairingKey(peerDeviceId), null) ?: return null
        return try {
            val json = JSONObject(stored)
            val wrapped = json.getString(FIELD_SECRET)
            PairingRecord(
                peerDeviceId = peerDeviceId,
                longTermSecret = unwrap(wrapped),
                peerName = json.optString(FIELD_NAME, ""),
                pairedAtMs = json.optLong(FIELD_PAIRED_AT, 0L),
                lastSeenAtMs = json.optLong(FIELD_LAST_SEEN, 0L),
                sasVerified = json.optBoolean(FIELD_SAS_VERIFIED, false)
            )
        } catch (exception: Exception) {
            // A record we cannot decrypt is useless and will only cause repeated
            // handshake failures. Drop it so the user can simply pair again.
            DiagnosticsLogger.log(
                "security",
                "Discarding unreadable pairing record",
                mapOf(
                    "peerDeviceId" to peerDeviceId.shortLabel(),
                    "errorType" to exception.javaClass.simpleName
                )
            )
            removePairing(peerDeviceId)
            null
        }
    }

    @Synchronized
    override fun savePairing(record: PairingRecord) {
        val json = JSONObject()
            .put(FIELD_SECRET, wrap(record.longTermSecret))
            .put(FIELD_NAME, record.peerName)
            .put(FIELD_PAIRED_AT, record.pairedAtMs)
            .put(FIELD_LAST_SEEN, record.lastSeenAtMs)
            .put(FIELD_SAS_VERIFIED, record.sasVerified)
        preferences.edit().putString(pairingKey(record.peerDeviceId), json.toString()).apply()
        DiagnosticsLogger.log(
            "security",
            "Stored pairing record",
            mapOf(
                "peerDeviceId" to record.peerDeviceId.shortLabel(),
                "sasVerified" to record.sasVerified
            )
        )
    }

    @Synchronized
    override fun touchPairing(peerDeviceId: DeviceId, nowMs: Long) {
        val existing = findPairing(peerDeviceId) ?: return
        savePairing(existing.copy(lastSeenAtMs = nowMs))
    }

    @Synchronized
    fun markSasVerified(peerDeviceId: DeviceId) {
        val existing = findPairing(peerDeviceId) ?: return
        savePairing(existing.copy(sasVerified = true))
    }

    @Synchronized
    fun removePairing(peerDeviceId: DeviceId) {
        preferences.edit().remove(pairingKey(peerDeviceId)).apply()
        DiagnosticsLogger.log(
            "security",
            "Removed pairing record",
            mapOf("peerDeviceId" to peerDeviceId.shortLabel())
        )
    }

    /**
     * Paired peers, for a "forget device" UI.
     *
     * Deliberately returns a summary and **never** the long-term secret. This
     * crosses the method channel into Dart, and key material has no business
     * leaving the native side.
     */
    @Synchronized
    fun pairedDevicesSummary(): List<Map<String, Any?>> = pairedDeviceIds().mapNotNull { id ->
        findPairing(id)?.let { record ->
            mapOf(
                "deviceId" to record.peerDeviceId.toString(),
                "shortId" to record.peerDeviceId.shortLabel(),
                "name" to record.peerName,
                "pairedAt" to record.pairedAtMs,
                "lastSeen" to record.lastSeenAtMs,
                "sasVerified" to record.sasVerified
            )
        }
    }

    @Synchronized
    fun pairedDeviceIds(): List<DeviceId> = preferences.all.keys
        .filter { it.startsWith(PAIRING_PREFIX) }
        .mapNotNull { DeviceId.parseOrNull(it.removePrefix(PAIRING_PREFIX)) }

    fun isPaired(peerDeviceId: DeviceId): Boolean =
        preferences.contains(pairingKey(peerDeviceId))

    /** Forgets every pairing and this device's identity. Used by "reset" in settings. */
    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
        try {
            keyStore().deleteEntry(KEYSTORE_ALIAS)
        } catch (exception: Exception) {
            DiagnosticsLogger.log(
                "security",
                "Failed to delete keystore entry",
                mapOf("errorType" to exception.javaClass.simpleName)
            )
        }
        DiagnosticsLogger.log("security", "Cleared all pairings and local identity")
    }

    // ------------------------------------------------------- keystore wrapping --

    /** Encrypts [secret] under the Keystore key. Returns `base64(iv):base64(ciphertext)`. */
    private fun wrap(secret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(secret)
        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(cipher.iv)}$SEPARATOR${encoder.encodeToString(ciphertext)}"
    }

    private fun unwrap(wrapped: String): ByteArray {
        val parts = wrapped.split(SEPARATOR)
        require(parts.size == 2) { "malformed wrapped secret" }
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    private fun wrappingKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Not requiring user authentication: the gateway has to be able to
                // re-key while the screen is locked. Documented trade-off.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun pairingKey(peerDeviceId: DeviceId): String = "$PAIRING_PREFIX$peerDeviceId"

    companion object {
        private const val PREFS_NAME = "wdcable_security"
        private const val KEY_DEVICE_ID = "local_device_id"
        private const val KEY_DISPLAY_NAME = "local_display_name"
        private const val PAIRING_PREFIX = "pairing."

        private const val FIELD_SECRET = "secret"
        private const val FIELD_NAME = "name"
        private const val FIELD_PAIRED_AT = "pairedAt"
        private const val FIELD_LAST_SEEN = "lastSeen"
        private const val FIELD_SAS_VERIFIED = "sasVerified"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "wdcable_pairing_wrap_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = ":"
    }
}
