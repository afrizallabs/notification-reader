package com.example.expensenotificationlistener

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.WorkManager
import java.security.MessageDigest
import java.time.Instant

class TransactionNotificationListener : NotificationListenerService() {
    private lateinit var prefs: NativePrefs

    override fun onCreate() {
        super.onCreate()
        prefs = NativePrefs(this)
    }

    override fun onListenerDisconnected() {
        prefs.setForwarding(false)
        WorkManager.getInstance(this)
            .cancelAllWorkByTag(ForwardWorker.WORK_TAG)
        NotificationListenerService.requestRebind(
            ComponentName(this, TransactionNotificationListener::class.java),
        )
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.isForwarding()) return
        if (sbn.packageName !in prefs.monitoredPackages()) return
        try {
            if (prefs.readConfiguration() == null) return
        } catch (_: Exception) {
            prefs.setLastError("Konfigurasi endpoint tersimpan tidak dapat dibuka.")
            return
        }

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = buildList {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?.takeIf(String::isNotBlank)?.let(::add)
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?.takeIf(String::isNotBlank)?.let(::add)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.map(CharSequence::toString)
                ?.filter(String::isNotBlank)
                ?.let(::addAll)
        }.distinct().joinToString("\n").take(3_500)
        val source = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        val expenseCandidate = ExpenseParser.isExpenseCandidate(title, body)
        val parsed = try {
            ExpenseParser.parse(
                title = title,
                body = body,
                source = source,
                notificationTimeMillis = sbn.postTime,
                rawConsent = prefs.includesRawText(),
            )
        } catch (_: RuntimeException) {
            prefs.setLastError("Satu notifikasi tidak dapat dibaca dengan aman.")
            return
        } ?: run {
            if (expenseCandidate) prefs.setLastSkipped(Instant.now().toString())
            return
        }

        val identity = "${sbn.packageName}:${sbn.key}"
        if (prefs.isDuplicateNotification(identity, System.currentTimeMillis())) return
        val workData = Data.Builder()
            .putString(ForwardWorker.INPUT_PACKAGE, sbn.packageName)
            .putString(ForwardWorker.INPUT_TIMESTAMP, parsed.timestamp)
            .putString(ForwardWorker.INPUT_MERCHANT, parsed.merchant)
            .putLong(ForwardWorker.INPUT_AMOUNT, parsed.amount)
            .putString(ForwardWorker.INPUT_SOURCE, parsed.source)
            .putString(ForwardWorker.INPUT_RAW, parsed.raw)
            .build()
        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(workData)
            .addTag(ForwardWorker.WORK_TAG)
            .setConstraints(ForwardWorker.NETWORK_CONSTRAINTS)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
