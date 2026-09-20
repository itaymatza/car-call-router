package org.carcallrouter.companion

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/** Privacy-safe process/UI facts used to diagnose cold-start and background binding failures. */
object ProcessDiagnostics {
    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var uiState = "NEVER_OPENED"

    fun processAgeMs(): Long = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)

    fun markUi(
        state: String,
        serviceBound: Boolean,
    ) {
        uiState = state
        RouterLog.event(
            "UI_LIFECYCLE",
            "state=$state; serviceBound=$serviceBound; processAgeMs=${processAgeMs()}",
        )
    }

    fun snapshot(context: Context): String {
        val processInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processInfo)
        val power = context.getSystemService(PowerManager::class.java)
        val usage = context.getSystemService(UsageStatsManager::class.java)
        return "processAgeMs=${processAgeMs()}; uiState=$uiState; importance=${processInfo.importance}; " +
            "interactive=${power?.isInteractive}; batteryExempt=${power?.isIgnoringBatteryOptimizations(context.packageName)}; " +
            "standbyBucket=${runCatching { usage?.appStandbyBucket }.getOrNull() ?: "unknown"}"
    }

    fun previousExit(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return "unavailable"
        val exit =
            runCatching { manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull()
                ?: return "none"
        val ageMs = (System.currentTimeMillis() - exit.timestamp).coerceAtLeast(0)
        return "reason=${exitReasonName(exit.reason)}; status=${exit.status}; ageMs=$ageMs"
    }

    private fun exitReasonName(reason: Int): String =
        when (reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER"
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "UNKNOWN_$reason"
        }
}
