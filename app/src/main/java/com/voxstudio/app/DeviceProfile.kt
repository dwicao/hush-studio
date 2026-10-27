package com.voxstudio.app

import android.app.ActivityManager
import android.content.Context

/**
 * Device-aware tuning. Defaults were measured on a 6 GB Moto G71 (2 threads fastest
 * for the ARMv8.2 engine). High-RAM phones such as Galaxy Z Fold 5 (Snapdragon 8 Gen 2,
 * 12 GB) can keep the model warm longer, use a bigger reuse cache, and run more
 * LM threads without starving the system.
 *
 * NPU note: Snapdragon Hexagon NPU has no GGML/QNN backend in this codebase, so
 * inference stays on CPU (dot-product + FP16 fast engine). These settings make the
 * CPU path as fast as possible on big-RAM devices instead of waiting on an NPU.
 */
object DeviceProfile {
    data class Profile(
        val totalMemMb: Long,
        val cores: Int,
        val highRam: Boolean,
        val defaultThreads: Int,
        val threadOptions: List<Int>,
        val vaeThreads: Int,
        val cacheMb: Int,
        val modelIdleMs: Long,
        val minFreeMemMb: Long
    )

    fun of(context: Context): Profile {
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        val totalMb = mem.totalMem / 1048576L
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        val highRam = totalMb >= 8000
        // SD 8 Gen 2 (8 cores: 1 X3 + 4 perf + 3 eff): 6 LM threads suits big-RAM phones.
        // Keep the user's choice clamped to what the phone actually has.
        val defaults = if (highRam) 6 else NativeCpuEngine.defaultThreads
        val options = if (highRam) listOf(2, 4, 6, 8).filter { it <= cores.coerceAtMost(8) }
        else listOf(2, 4).filter { it <= cores }
        return Profile(
            totalMemMb = totalMb,
            cores = cores,
            highRam = highRam,
            defaultThreads = defaults.coerceIn(options.minOrNull() ?: 2, options.maxOrNull() ?: defaults),
            threadOptions = options.ifEmpty { listOf(defaults) },
            // AudioVAE spreads badly to little cores on old phones (see mobile_session.inc);
            // SD 8 Gen 2 efficiency cores are much faster, so allow up to 4 there.
            vaeThreads = if (highRam) 4 else 2,
            cacheMb = if (highRam) 128 else 32,
            modelIdleMs = if (highRam) 300_000L else 90_000L,
            // Admission: need enough free RAM to hold weights + KV + compute arena.
            // 0.5B peaks ~1.7 GB; a 2B-class model (VoxCPM2) needs ~3-4 GB.
            minFreeMemMb = if (highRam) 1200 else 700
        )
    }

    /** Clamp a stored thread count to what this phone supports. */
    fun clampThreads(context: Context, stored: Int): Int {
        val p = of(context)
        val opts = p.threadOptions.ifEmpty { listOf(stored) }
        if (stored in opts) return stored
        val lo = opts.filter { it <= stored }.maxOrNull()
        val hi = opts.filter { it >= stored }.minOrNull()
        return when {
            lo == null -> hi ?: stored
            hi == null -> lo
            else -> if (stored - lo <= hi - stored) lo else hi
        }
    }
}
