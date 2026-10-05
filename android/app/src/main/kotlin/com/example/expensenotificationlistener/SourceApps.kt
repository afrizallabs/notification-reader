package com.example.expensenotificationlistener

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object SourceApps {
    fun list(context: Context): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val manager = context.packageManager
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return activities
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                mapOf(
                    "package" to packageName,
                    "label" to info.loadLabel(manager).toString(),
                )
            }
            .distinctBy { it["package"] }
            .sortedBy { it["label"]?.lowercase() }
    }
}
