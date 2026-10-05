package com.example.expensenotificationlistener

import android.content.Intent
import android.provider.Settings
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val prefs = NativePrefs(this)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "id.expenselistener/native/v1",
        ).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getStatus" -> result.success(prefs.statusMap(this))
                    "getSourceApps" -> result.success(SourceApps.list(this))
                    "saveConfiguration" -> {
                        val args = call.arguments as? Map<*, *>
                            ?: throw IllegalArgumentException("Invalid configuration.")
                        val url = args["url"] as? String ?: ""
                        val secret = args["secret"] as? String ?: ""
                        val packages = (args["packages"] as? List<*>)
                            ?.mapNotNull { it as? String }.orEmpty()
                        val rawConsent = args["rawConsent"] as? Boolean ?: false
                        prefs.saveConfiguration(url, secret, packages, rawConsent)
                        result.success(null)
                    }
                    "testConnection" -> {
                        executor.execute {
                            try {
                                val config = prefs.readConfiguration()
                                    ?: throw IllegalStateException("Simpan URL dan kunci rahasia terlebih dahulu.")
                                AppsScriptClient.ping(config)
                                runOnUiThread {
                                    prefs.clearLastError()
                                    result.success(mapOf("ok" to true, "message" to "Koneksi berhasil."))
                                }
                            } catch (error: Exception) {
                                val safeMessage = error.message ?: "Tes koneksi gagal."
                                prefs.setLastError(safeMessage)
                                runOnUiThread {
                                    result.error("connection_failed", safeMessage, null)
                                }
                            }
                        }
                    }
                    "setForwarding" -> {
                        val enabled = (call.arguments as? Map<*, *>)?.get("enabled") as? Boolean
                            ?: false
                        if (enabled) {
                            val config = prefs.readConfiguration()
                            require(config != null) {
                                "Konfigurasikan dan simpan URL Apps Script terlebih dahulu."
                            }
                            require(prefs.statusMap(this)["listenerEnabled"] == true) {
                                "Berikan akses notifikasi melalui pengaturan Android terlebih dahulu."
                            }
                            require(prefs.monitoredPackages().isNotEmpty()) {
                                "Pilih setidaknya satu aplikasi sumber terlebih dahulu."
                            }
                        } else {
                            WorkManager.getInstance(this)
                                .cancelAllWorkByTag(ForwardWorker.WORK_TAG)
                        }
                        prefs.setForwarding(enabled)
                        result.success(null)
                    }
                    "openNotificationSettings" -> {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        result.success(null)
                    }
                    "clearConfiguration" -> {
                        prefs.clearConfiguration()
                        WorkManager.getInstance(this)
                            .cancelAllWorkByTag(ForwardWorker.WORK_TAG)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            } catch (error: Exception) {
                result.error("native_error", error.message ?: "Operasi Android gagal.", null)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
