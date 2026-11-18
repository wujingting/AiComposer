package com.example.aicomposer.coach

import com.example.aicomposer.i18n.tr
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * All thresholds of the rule engine.
 *
 * Defaults are in DEFAULTS below. On first launch the app exports them to the phone:
 *   Android/data/com.example.aicomposer/files/coach_config.json
 * Edit that file from a computer (USB) or a file manager on the phone and restart the app; no rebuild needed.
 * To restore the defaults: delete the file and restart the app.
 */
object Cfg {
    val DEFAULTS: Map<String, Double> = linkedMapOf(
        // --- Penalties and total score ---
        "penalty.info" to 4.0,
        "penalty.warn" to 12.0,
        "penalty.error" to 25.0,
        "score.scale" to 60.0,              // score = 100 × e^(−penalty/scale); larger = gentler
        "subject.guess_penalty" to 0.4,     // penalty factor for subject-position advice when the subject is only a guess

        // --- Temporal smoothing ---
        "temporal.window_ms" to 1500.0,     // how many milliseconds to look back
        "temporal.show" to 0.6,             // report when the share within the window is >= this
        "temporal.hide" to 0.3,             // a reported problem disappears when the share drops below this

        // --- Composition ---
        "placement.ok_dist" to 0.06,        // how close to a thirds point counts as placed (fraction of frame width)
        "placement.warn_dist" to 0.15,
        "placement.max_penalty" to 25.0,
        "symmetry.min" to 0.75,             // above this symmetry, judge as a symmetric composition
        "size.small_area" to 0.015,         // subject smaller than 1.5% of the frame is too small
        "size.big_area" to 0.75,
        "lead_room.min_yaw" to 20.0,        // head turn (degrees) needed to count as "facing a direction"
        "lead_room.margin" to 0.1,
        "merger.peak" to 0.6,               // share of vertical edges in one column above the head
        "merger.ratio" to 2.5,
        "clutter.warn" to 26.0,             // texture strength around the subject
        "clutter.info" to 18.0,
        "separation.min_diff" to 0.12,      // color difference between subject and background
        "edge.bright_fraction" to 0.12,     // share of bright patches at the edges
        "balance.offset" to 0.3,
        "tilt.ok_deg" to 1.0,
        "tilt.warn_deg" to 2.5,
        "tilt.penalty_per_deg" to 6.0,

        // --- Light ---
        "exposure.under_ev" to 0.7,         // how many stops darker than middle grey counts as "dark"
        "exposure.over_ev" to 0.7,
        "exposure.night_under_ev" to 1.5,   // night scenes are dark anyway, so be more lenient
        "backlight.ratio" to 0.6,           // subject brightness < overall × this ratio
        "backlight.min_clip" to 0.02,
        "clip.highlight_warn" to 0.05,
        "clip.shadow_info" to 0.12,
        "face_clip.fraction" to 0.1,
        "dr.highlight" to 0.02,
        "dr.shadow" to 0.05,
        "hard_light.std" to 0.22,           // standard deviation of face brightness
        "wb.mixed" to 0.35,
        "wb.warm" to 1.45,                  // R/B ratio
        "wb.cool" to 0.75,

        // --- Focus / settings ---
        "iso.max_base" to 3200.0,           // ISO limit without cropping
        "shutter.error_ratio" to 2.0,       // how many times slower than required counts as a "problem"
        "shutter.warn_ratio" to 1.2,
        "dof.side_face_depth" to 0.06,      // meters
        "dof.thin_depth" to 0.04,
        "focus.ratio" to 0.6,
        "focus.min_bg" to 6.0,
        "shake.motion" to 0.12,

        // --- Portrait ---
        "eyes.closed_prob" to 0.3,
        "smile.min" to 0.15,
        "eye_line.tolerance" to 0.1,
        "joint_cut.distance" to 0.025,

        // --- Landscape ---
        "horizon.center_band" to 0.08,      // horizon within 0.5 ± this counts as "dead center"
        "sky.max" to 0.8,
        "sky.min" to 0.12,
        "foreground.min_edge" to 6.0,
        "verticals.deg" to 1.5,

        // --- Everyday snapshots ---
        "snap.max_blobs" to 3.0,            // this many equally eye-catching regions or more = "no focal point"
        "snap.closer_area" to 0.05,         // subject smaller than 5% of the frame -> "get a bit closer"
        "snap.flat_saturation" to 0.12,     // mean saturation below this + low contrast -> flat image
        "snap.flat_contrast" to 0.12,
        "snap.shadow_contrast" to 0.28,     // contrast above this counts as "strong light and shadow"
        "snap.diagonal_ratio" to 0.35,      // diagonal share of strong edges above this -> leading lines

        "interest.min_margin" to 0.015,     // CLIP point of interest: how far the best score must exceed the median
        "text.tilt_deg" to 4.0,             // warn when text is tilted more than this many degrees

        // --- Aesthetics / auto crop ---
        "aesthetic.low_norm" to 30.0,
        "autocrop.face_h" to 0.15,          // good face height as a share of the final image height
        "autocrop.object_area" to 0.12,     // good subject area as a share of the final image
        "autocrop.min_keep" to 0.5,
        "ai.threads" to 2.0,                // CPU threads for the aesthetic model (ONNX Runtime); more = faster but hotter

        // --- Direction arrows on the viewfinder (subject offset as a fraction of the frame) ---
        "guide.show" to 0.05,               // show an arrow once the subject is this far from where it should be
        "guide.hide" to 0.03,               // hide it again below this (hysteresis, no flicker)
        "guide.min_guided_ms" to 700.0,     // an arrow must have been shown this long before "in place" counts
        "guide.settle_ms" to 400.0,         // ...and the frame must then stay in place this long before the buzz
        "guide.cooldown_ms" to 4000.0,      // at most one "in place" buzz per this many milliseconds
    )

    @Volatile
    private var values: Map<String, Double> = DEFAULTS

    fun d(key: String): Double = values[key] ?: DEFAULTS[key] ?: error("Unknown config key $key")
    fun f(key: String): Float = d(key).toFloat()

    fun weight(s: Severity): Float = when (s) {
        Severity.INFO -> f("penalty.info")
        Severity.WARN -> f("penalty.warn")
        Severity.ERROR -> f("penalty.error")
        else -> 0f
    }

    /** Reads the config file on the phone; exports the defaults if there is none. Returns a status message. */
    fun load(context: Context): String {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "coach_config.json")
        return try {
            if (!file.exists()) {
                val o = JSONObject()
                DEFAULTS.forEach { (k, v) -> o.put(k, v) }
                file.writeText(o.toString(2))
                values = DEFAULTS
                tr("已导出默认配置：${file.absolutePath}", "デフォルト設定を書き出しました：${file.absolutePath}", "Exported default config: ${file.absolutePath}")
            } else {
                val o = JSONObject(file.readText())
                val m = DEFAULTS.toMutableMap()
                var changed = 0
                for (k in o.keys()) {
                    if (k in DEFAULTS) {
                        val v = o.getDouble(k)
                        if (v != DEFAULTS[k]) changed++
                        m[k] = v
                    }
                }
                values = m
                tr("已读取配置（$changed 项与默认不同）", "設定を読み込みました（$changed 項目がデフォルトと異なります）", "Config loaded ($changed values differ from the defaults)")
            }
        } catch (e: Exception) {
            Log.e("Cfg", "Failed to read config", e)
            values = DEFAULTS
            tr("配置文件有误，已使用默认值：${e.message}", "設定ファイルに誤りがあるためデフォルト値を使用します：${e.message}", "Config file is invalid, using defaults: ${e.message}")
        }
    }
}
