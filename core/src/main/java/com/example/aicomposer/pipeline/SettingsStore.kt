package com.example.aicomposer.pipeline

import android.content.Context
import com.example.aicomposer.analysis.CropPlan

/** Settings that are persisted. */
data class Settings(
    val focalLength: Int = 35,
    val cropMode: CropMode = CropMode.AUTO,
    val cropKeep: Float = 1f,
    val cropAspect: Float = 1.5f,
    val autoThreshold: Int = 80,
    /** Ignored rules: "scene:ruleId", e.g. "PORTRAIT:placement". */
    val ignoredRules: Set<String> = emptySet(),
    /** Rule id -> display name (for the "ignored" list). */
    val ruleNames: Map<String, String> = emptyMap(),
) {
    val cropPlan get() = CropPlan(cropKeep, cropAspect)
}

/** Persists settings in SharedPreferences so they are restored the next time the app opens. */
class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        focalLength = sp.getInt("focal", 35),
        cropMode = runCatching { CropMode.valueOf(sp.getString("crop_mode", CropMode.AUTO.name)!!) }
            .getOrDefault(CropMode.AUTO),
        cropKeep = sp.getFloat("crop_keep", 1f),
        cropAspect = sp.getFloat("crop_aspect", 1.5f),
        autoThreshold = sp.getInt("auto_threshold", 80),
        ignoredRules = sp.getStringSet("ignored", emptySet())?.toSet().orEmpty(),
        ruleNames = sp.getStringSet("rule_names", emptySet()).orEmpty()
            .mapNotNull { s -> s.split("|", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap(),
    )

    fun save(s: Settings) {
        sp.edit()
            .putInt("focal", s.focalLength)
            .putString("crop_mode", s.cropMode.name)
            .putFloat("crop_keep", s.cropKeep)
            .putFloat("crop_aspect", s.cropAspect)
            .putInt("auto_threshold", s.autoThreshold)
            .putStringSet("ignored", s.ignoredRules)
            .putStringSet("rule_names", s.ruleNames.map { (k, v) -> "$k|$v" }.toSet())
            .apply()
    }
}
