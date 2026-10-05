package com.example.expensenotificationlistener

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.work.WorkManager
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EndpointConfig(val url: String, val secret: String)

class NativePrefs(private val context: Context) {
    private val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)

    fun saveConfiguration(
        url: String,
        secret: String,
        packages: List<String>,
        rawConsent: Boolean,
    ) {
        val wasRawConsentEnabled = values.getBoolean(KEY_RAW_CONSENT, false)
        val endpoint = URL(url)
        require(
            endpoint.protocol == "https" &&
                endpoint.host == "script.google.com" &&
                endpoint.path.startsWith("/macros/s/") &&
                endpoint.path.endsWith("/exec") &&
                endpoint.port in listOf(-1, 443) &&
                endpoint.userInfo == null,
        ) {
            "Gunakan URL deployment web app Apps Script yang benar."
        }
        val current = if (secret.isBlank()) readConfiguration() else null
        val effectiveSecret = secret.ifBlank { current?.secret.orEmpty() }
        require(effectiveSecret.length in MIN_SECRET_LENGTH..MAX_SECRET_LENGTH) {
            "Kunci rahasia harus berisi 32 sampai 256 karakter."
        }

        val payload = JSONObject()
            .put("url", url)
            .put("secret", effectiveSecret)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        secure.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()

        values.edit()
            .putStringSet(KEY_PACKAGES, packages.toSet())
            .putBoolean(KEY_RAW_CONSENT, rawConsent)
            .apply()
        if (wasRawConsentEnabled && !rawConsent) {
            WorkManager.getInstance(context).cancelAllWorkByTag(ForwardWorker.WORK_TAG)
            setLastError("Pengiriman tertunda dihapus karena izin teks notifikasi dicabut.")
        }
    }

    fun readConfiguration(): EndpointConfig? {
        val encodedIv = secure.getString(KEY_IV, null) ?: return null
        val encodedData = secure.getString(KEY_DATA, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
        )
        val json = JSONObject(
            String(cipher.doFinal(Base64.decode(encodedData, Base64.NO_WRAP)), Charsets.UTF_8),
        )
        return EndpointConfig(json.getString("url"), json.getString("secret"))
    }

    fun statusMap(context: Context): Map<String, Any?> {
        val config = readConfiguration()
        val listenerEnabled = isListenerEnabled(context)
        if (!listenerEnabled && isForwarding()) {
            setForwarding(false)
            WorkManager.getInstance(context).cancelAllWorkByTag(ForwardWorker.WORK_TAG)
        }
        return mapOf(
            "configured" to (config != null),
            "url" to config?.url,
            "packages" to monitoredPackages(),
            "rawConsent" to values.getBoolean(KEY_RAW_CONSENT, false),
            "forwarding" to values.getBoolean(KEY_FORWARDING, false),
            "listenerEnabled" to listenerEnabled,
            "lastSuccess" to values.getString(KEY_LAST_SUCCESS, null),
            "lastError" to values.getString(KEY_LAST_ERROR, null),
            "lastSkipped" to values.getString(KEY_LAST_SKIPPED, null),
        )
    }

    fun monitoredPackages(): List<String> =
        values.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toList()

    fun isForwarding(): Boolean = values.getBoolean(KEY_FORWARDING, false)

    fun includesRawText(): Boolean = values.getBoolean(KEY_RAW_CONSENT, false)

    fun listenerAccessEnabled(): Boolean = isListenerEnabled(context)

    fun setForwarding(enabled: Boolean) {
        values.edit().putBoolean(KEY_FORWARDING, enabled).apply()
    }

    fun setLastSuccess(timestamp: String) {
        values.edit().putString(KEY_LAST_SUCCESS, timestamp).remove(KEY_LAST_ERROR).apply()
    }

    fun setLastError(message: String) {
        values.edit().putString(KEY_LAST_ERROR, message.take(240)).apply()
    }

    fun clearLastError() {
        values.edit().remove(KEY_LAST_ERROR).apply()
    }

    fun setLastSkipped(timestamp: String) {
        values.edit().putString(KEY_LAST_SKIPPED, timestamp).apply()
    }

    fun isDuplicateNotification(identity: String, now: Long): Boolean {
        val hash = identity.sha256()
        val key = "notification:$hash"
        val previous = values.getLong(key, 0L)
        val editor = values.edit().putLong(key, now)
        values.all.forEach { (storedKey, storedValue) ->
            if (storedKey != key &&
                storedKey.startsWith("notification:") &&
                storedValue is Long &&
                now - storedValue > DEDUPE_RETENTION_MS
            ) {
                editor.remove(storedKey)
            }
        }
        editor.apply()
        if (now - previous < DUPLICATE_WINDOW_MS && previous > 0L) return true
        return false
    }

    fun clearConfiguration() {
        secure.edit().clear().apply()
        values.edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (error: Exception) {
            throw IllegalStateException("Could not clear protected configuration.", error)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun isListenerEnabled(context: Context): Boolean {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val component = ComponentName(context, TransactionNotificationListener::class.java)
        return listeners.split(':').any { it == component.flattenToString() }
    }

    companion object {
        private const val PREFS = "operational_status"
        private const val SECURE_PREFS = "encrypted_configuration"
        private const val KEY_ALIAS = "expense_forwarder_config_v1"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"
        private const val KEY_PACKAGES = "source_packages"
        private const val KEY_RAW_CONSENT = "raw_consent"
        private const val KEY_FORWARDING = "forwarding"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SKIPPED = "last_skipped"
        private const val DUPLICATE_WINDOW_MS = 30_000L
        private const val DEDUPE_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private const val MIN_SECRET_LENGTH = 32
        private const val MAX_SECRET_LENGTH = 256
    }
}
