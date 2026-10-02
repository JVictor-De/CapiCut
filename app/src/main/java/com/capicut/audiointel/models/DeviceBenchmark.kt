package com.capicut.audiointel.models

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build

enum class QualityTier { LOW, MEDIUM, HIGH }

enum class QualityPreset { FAST, BALANCED, MAX }

/**
 * Heuristic on first launch → maps device to QualityTier.
 * Manual preset overrides automatic tier for model selection.
 */
class DeviceBenchmark(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasBenchmark(): Boolean = prefs.contains(KEY_TIER)

    fun storedTier(): QualityTier? =
        prefs.getString(KEY_TIER, null)?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() }

    fun storedPreset(): QualityPreset =
        prefs.getString(KEY_PRESET, QualityPreset.BALANCED.name)
            ?.let { runCatching { QualityPreset.valueOf(it) }.getOrNull() }
            ?: QualityPreset.BALANCED

    fun setPreset(preset: QualityPreset) {
        prefs.edit().putString(KEY_PRESET, preset.name).apply()
    }

    fun runIfNeeded(): QualityTier {
        storedTier()?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val ramGb = mem.totalMem / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        val sdk = Build.VERSION.SDK_INT
        val tier = when {
            ramGb >= 8 && cores >= 8 && sdk >= 31 -> QualityTier.HIGH
            ramGb >= 4 && cores >= 6 -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }
        prefs.edit().putString(KEY_TIER, tier.name).apply()
        return tier
    }

    fun effectiveTier(): QualityTier {
        val auto = runIfNeeded()
        return when (storedPreset()) {
            QualityPreset.FAST -> QualityTier.LOW
            QualityPreset.MAX -> QualityTier.HIGH
            QualityPreset.BALANCED -> auto
        }
    }

    companion object {
        private const val PREFS = "capicut_benchmark"
        private const val KEY_TIER = "tier"
        private const val KEY_PRESET = "preset"
    }
}
