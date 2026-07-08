package com.hivirtus.zygiskmode

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

object ForegroundAppHelper {

    fun foregroundPackage(context: Context): String? {
        foregroundPackageViaSu()?.let { return it }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usage != null) {
                    val end = System.currentTimeMillis()
                    val stats = usage.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        end - TimeUnit.MINUTES.toMillis(2),
                        end
                    )
                    val top = stats?.maxByOrNull { it.lastTimeUsed }
                    if (top != null && top.lastTimeUsed > end - TimeUnit.SECONDS.toMillis(8)) {
                        return top.packageName
                    }
                }
            }
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            if (!tasks.isNullOrEmpty()) {
                return tasks[0].topActivity?.packageName
            }
        } catch (_: Exception) {}
        return null
    }

    private fun foregroundPackageViaSu(): String? {
        val output = ShellHelper.runSuOutput(
            "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity' | head -1"
        ) ?: return null
        val match = Regex("""([a-zA-Z0-9_.]+)/[a-zA-Z0-9_.]+""").find(output) ?: return null
        return match.groupValues.getOrNull(1)
    }
}
