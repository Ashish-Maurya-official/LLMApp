package com.example.llmapp.core.inference

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Safeguards GPU/NPU initialization against native driver SIGSEGV and OOM crashes.
 * Uses persistent crash flags, library checks, memory pressure thresholds, and SoC detection.
 */
class GpuCapabilityProbe(context: Context) {

    companion object {
        private const val TAG = "GpuCapabilityProbe"
        private const val PREFS_NAME = "gpu_capability_probe"
        private const val KEY_GPU_LAST_ATTEMPT = "gpu_last_attempt_status"
        private const val KEY_NPU_LAST_ATTEMPT = "npu_last_attempt_status"
        private const val KEY_GPU_CRASH_COUNT = "gpu_crash_count"
        private const val KEY_NPU_CRASH_COUNT = "npu_crash_count"
        private const val KEY_GPU_USER_OVERRIDE = "gpu_user_override"

        private const val STATUS_NONE = "none"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_SUCCESS = "success"

        /** Minimum available RAM (in MB) to even attempt GPU loading */
        private const val MIN_AVAILABLE_RAM_MB = 512L

        /** After this many consecutive GPU crashes, permanently block GPU */
        private const val MAX_CRASH_COUNT = 3
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ── Cached probe results (computed once per app session) ──────────────
    private var openClAvailable: Boolean? = null
    private var vulkanAvailable: Boolean? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Checks if initializing with Backend.GPU() is safe.
     */
    fun isGpuSafe(): Boolean {
        // 1. Check if user has explicitly disabled GPU (after too many crashes)
        if (prefs.getBoolean(KEY_GPU_USER_OVERRIDE, false)) {
            Log.w(TAG, "GPU blocked by user override flag")
            return false
        }

        // 2. Check crash history — if last attempt was "pending", it means SIGSEGV killed us
        val lastStatus = prefs.getString(KEY_GPU_LAST_ATTEMPT, STATUS_NONE) ?: STATUS_NONE
        if (lastStatus == STATUS_PENDING) {
            // The process was killed during GPU init — record the crash
            val crashCount = prefs.getInt(KEY_GPU_CRASH_COUNT, 0) + 1
            prefs.edit()
                .putString(KEY_GPU_LAST_ATTEMPT, STATUS_NONE)
                .putInt(KEY_GPU_CRASH_COUNT, crashCount)
                .apply()
            Log.e(TAG, "GPU crash detected (process died during init). Total crashes: $crashCount")

            if (crashCount >= MAX_CRASH_COUNT) {
                Log.e(TAG, "GPU permanently blocked after $MAX_CRASH_COUNT consecutive crashes")
                return false
            }
        }

        // 3. Check if too many cumulative crashes
        val totalCrashes = prefs.getInt(KEY_GPU_CRASH_COUNT, 0)
        if (totalCrashes >= MAX_CRASH_COUNT) {
            Log.w(TAG, "GPU blocked: $totalCrashes cumulative crashes (threshold: $MAX_CRASH_COUNT)")
            return false
        }

        // 4. Check OpenCL availability — LiteRT-LM's GPU delegate requires OpenCL.
        if (!isOpenClAvailable()) {
            Log.w(TAG, "GPU blocked: OpenCL library not available on this device")
            return false
        }

        // 5. Check available memory
        if (!hasEnoughMemory()) {
            Log.w(TAG, "GPU blocked: insufficient available RAM")
            return false
        }

        Log.d(TAG, "GPU probe PASSED — safe to attempt GPU init")
        return true
    }

    /**
     * Checks if initializing with Backend.NPU() is safe.
     */
    fun isNpuSafe(): Boolean {
        // 1. Check crash history
        val lastStatus = prefs.getString(KEY_NPU_LAST_ATTEMPT, STATUS_NONE) ?: STATUS_NONE
        if (lastStatus == STATUS_PENDING) {
            val crashCount = prefs.getInt(KEY_NPU_CRASH_COUNT, 0) + 1
            prefs.edit()
                .putString(KEY_NPU_LAST_ATTEMPT, STATUS_NONE)
                .putInt(KEY_NPU_CRASH_COUNT, crashCount)
                .apply()
            Log.e(TAG, "NPU crash detected. Total NPU crashes: $crashCount")
            if (crashCount >= MAX_CRASH_COUNT) return false
        }

        val totalCrashes = prefs.getInt(KEY_NPU_CRASH_COUNT, 0)
        if (totalCrashes >= MAX_CRASH_COUNT) {
            Log.w(TAG, "NPU blocked: too many crashes")
            return false
        }

        // 2. Log SoC info for NPU (non-Qualcomm SoCs are tried but warned, as crash detector will catch failures)
        if (!isQualcommDevice()) {
            Log.w(TAG, "NPU warning: non-Qualcomm SoC detected (${Build.HARDWARE}, ${Build.SOC_MODEL}). Attempting execution anyway...")
        }

        // 3. Check memory
        if (!hasEnoughMemory()) {
            Log.w(TAG, "NPU blocked: insufficient available RAM")
            return false
        }

        Log.d(TAG, "NPU probe PASSED")
        return true
    }

    /**
     * Sets the "pending" flag before GPU init to track potential native SIGSEGV crashes.
     */
    fun markGpuInitStarted() {
        prefs.edit().putString(KEY_GPU_LAST_ATTEMPT, STATUS_PENDING).commit() // commit() not apply() — must be synchronous
        Log.d(TAG, "GPU init marked as PENDING (crash detection armed)")
    }

    /**
     * Resets GPU crash flags and counters on successful initialization.
     */
    fun markGpuInitSucceeded() {
        prefs.edit()
            .putString(KEY_GPU_LAST_ATTEMPT, STATUS_SUCCESS)
            .putInt(KEY_GPU_CRASH_COUNT, 0) // Reset counter on success
            .apply()
        Log.d(TAG, "GPU init marked as SUCCESS — crash counter reset")
    }

    /** Same pattern for NPU */
    fun markNpuInitStarted() {
        prefs.edit().putString(KEY_NPU_LAST_ATTEMPT, STATUS_PENDING).commit()
    }

    fun markNpuInitSucceeded() {
        prefs.edit()
            .putString(KEY_NPU_LAST_ATTEMPT, STATUS_SUCCESS)
            .putInt(KEY_NPU_CRASH_COUNT, 0)
            .apply()
    }

    /**
     * Resets all crash and override flags to allow backend retries.
     */
    fun resetCrashHistory() {
        prefs.edit()
            .putString(KEY_GPU_LAST_ATTEMPT, STATUS_NONE)
            .putString(KEY_NPU_LAST_ATTEMPT, STATUS_NONE)
            .putInt(KEY_GPU_CRASH_COUNT, 0)
            .putInt(KEY_NPU_CRASH_COUNT, 0)
            .putBoolean(KEY_GPU_USER_OVERRIDE, false)
            .apply()
        // Also clear cached results so they're re-probed
        openClAvailable = null
        vulkanAvailable = null
        Log.d(TAG, "All crash history reset")
    }

    /**
     * Returns a human-readable diagnostic string for the UI.
     */
    fun getDiagnostics(): String = buildString {
        appendLine("=== GPU Capability Probe ===")
        appendLine("OpenCL available: ${isOpenClAvailable()}")
        appendLine("Vulkan available: ${isVulkanAvailable()}")
        appendLine("GPU crash count: ${prefs.getInt(KEY_GPU_CRASH_COUNT, 0)}")
        appendLine("NPU crash count: ${prefs.getInt(KEY_NPU_CRASH_COUNT, 0)}")
        appendLine("GPU last status: ${prefs.getString(KEY_GPU_LAST_ATTEMPT, STATUS_NONE)}")
        appendLine("NPU last status: ${prefs.getString(KEY_NPU_LAST_ATTEMPT, STATUS_NONE)}")
        appendLine("Qualcomm SoC: ${isQualcommDevice()}")
        appendLine("Available RAM: ${getAvailableRamMb()} MB")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        appendLine("GPU safe: ${isGpuSafe()}")
        appendLine("NPU safe: ${isNpuSafe()}")
    }

    // ── Internal checks ──────────────────────────────────────────────────────

    private fun isOpenClAvailable(): Boolean {
        if (openClAvailable != null) return openClAvailable!!
        openClAvailable = try {
            System.loadLibrary("OpenCL")
            Log.d(TAG, "OpenCL library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "OpenCL library NOT available: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "OpenCL library blocked by security policy: ${e.message}")
            false
        }
        return openClAvailable!!
    }

    private fun isVulkanAvailable(): Boolean {
        if (vulkanAvailable != null) return vulkanAvailable!!
        vulkanAvailable = try {
            System.loadLibrary("vulkan")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
        return vulkanAvailable!!
    }

    private fun isQualcommDevice(): Boolean {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()

        // Check SOC_MANUFACTURER on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val socMfg = Build.SOC_MANUFACTURER.lowercase()
            if (socMfg.contains("qualcomm") || socMfg.contains("qcom")) return true
        }

        // Fallback: check hardware/board strings for known Qualcomm identifiers
        val qualcommIndicators = listOf("qcom", "qualcomm", "kona", "lahaina", "taro", 
            "kalama", "pineapple", "sun", "crow", "sm8", "sm7", "sdm", "msm")
        return qualcommIndicators.any { hardware.contains(it) || board.contains(it) }
    }

    private fun hasEnoughMemory(): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        Log.d(TAG, "Available RAM: ${availMb}MB (threshold: ${MIN_AVAILABLE_RAM_MB}MB)")
        return availMb >= MIN_AVAILABLE_RAM_MB
    }

    private fun getAvailableRamMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }
}
