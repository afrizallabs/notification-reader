package com.example.expensenotificationlistener

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant

class ForwardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = NativePrefs(applicationContext)
        if (!prefs.isForwarding()) return Result.failure()
        if (!prefs.listenerAccessEnabled()) {
            prefs.setForwarding(false)
            prefs.setLastError("Akses notifikasi nonaktif. Aktifkan kembali melalui pengaturan Android.")
            return Result.failure()
        }
        val sourcePackage = inputData.getString(INPUT_PACKAGE) ?: return Result.failure()
        if (sourcePackage !in prefs.monitoredPackages()) {
            return Result.failure()
        }
        val config = try {
            prefs.readConfiguration()
        } catch (_: Exception) {
            prefs.setLastError("Konfigurasi endpoint tersimpan tidak dapat dibuka.")
            return Result.failure()
        } ?: return Result.failure()
        val timestamp = inputData.getString(INPUT_TIMESTAMP) ?: return Result.failure()
        val merchant = inputData.getString(INPUT_MERCHANT) ?: return Result.failure()
        val source = inputData.getString(INPUT_SOURCE) ?: return Result.failure()
        val amount = inputData.getLong(INPUT_AMOUNT, 0L)
        val raw = inputData.getString(INPUT_RAW).orEmpty()
        if (amount <= 0L) return Result.failure()

        return try {
            AppsScriptClient.post(
                config,
                mapOf(
                    "timestamp" to timestamp,
                    "merchant" to merchant,
                    "amount" to amount,
                    "source" to source,
                    "raw" to if (prefs.includesRawText()) raw else "",
                ),
            )
            prefs.setLastSuccess(Instant.now().toString())
            Result.success()
        } catch (error: DeliveryException) {
            prefs.setLastError(error.safeMessage)
            if (error.retryable && runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (_: Exception) {
            prefs.setLastError("Kesalahan pengiriman tak terduga. Periksa konfigurasi endpoint.")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_TAG = "notification-forward"
        const val INPUT_PACKAGE = "source_package"
        const val INPUT_TIMESTAMP = "timestamp"
        const val INPUT_MERCHANT = "merchant"
        const val INPUT_AMOUNT = "amount"
        const val INPUT_SOURCE = "source"
        const val INPUT_RAW = "raw"
        private const val MAX_RETRIES = 5

        val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
