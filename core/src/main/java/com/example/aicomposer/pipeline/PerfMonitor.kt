package com.example.aicomposer.pipeline

import com.example.aicomposer.i18n.tr
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Performance monitoring + automatic throttling.
 *  - Records the time of each analysis (moving average)
 *  - Reads the phone's thermal status (Android 10+)
 *  - Slows the analysis down when too slow or too hot; pauses AI aesthetics when severe
 */
class PerfMonitor(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private var emaMs = 0.0

    data class Stats(
        val analysisMs: Int = 0,
        val intervalMs: Long = 250,
        val thermal: String = "",
        val level: Int = 0,          // 0 normal; 1 throttled; 2 severe (aesthetics paused)
    ) {
        val fps: Float get() = 1000f / (analysisMs + intervalMs).coerceAtLeast(1)
    }

    fun record(ms: Long): Stats {
        emaMs = if (emaMs == 0.0) ms.toDouble() else emaMs * 0.8 + ms * 0.2
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        val thermalText = when (thermalStatus) {
            0 -> tr("正常", "正常", "normal")
            1 -> tr("微热", "やや高め", "warm")
            2 -> tr("偏热", "高め", "hot")
            3 -> tr("很热", "かなり高い", "very hot")
            else -> tr("过热", "過熱", "overheating")
        }
        val level = when {
            thermalStatus >= 3 -> 2                     // THERMAL_STATUS_SEVERE and above
            thermalStatus == 2 || emaMs > 350 -> 1      // THERMAL_STATUS_MODERATE or analysis too slow
            else -> 0
        }
        val interval = when (level) {
            2 -> 1000L
            1 -> 500L
            else -> 250L
        }
        return Stats(emaMs.toInt(), interval, thermalText, level)
    }
}
