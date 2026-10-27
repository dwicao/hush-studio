package com.voxstudio.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics log: in-memory ring buffer plus one bounded session file
 * per launch in app-private storage. Nothing leaves the phone unless the user
 * explicitly exports the file.
 *
 * Privacy: NEVER log script text, transcripts, voice names, report notes or file
 * contents here. Sizes, counts, durations and stage names only. Absolute app paths
 * are scrubbed to "<app>".
 */
object AppLog {
    private const val MAX_MEM = 400
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val KEEP_FILES = 3
    private val lock = Any()
    private val mem = ArrayDeque<String>()
    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var appPath: String? = null
    @Volatile private var hooked = false

    fun init(appDir: File, version: String) {
        synchronized(lock) {
            if (dir != null) return
            dir = File(appDir, "logs").also { it.mkdirs() }
            appPath = appDir.absolutePath
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = File(dir, "app-$stamp.log")
            // Keep newest sessions only, leaving room for the file just created.
            dir!!.listFiles { f -> f.name.startsWith("app-") && f.extension == "log" }
                ?.sortedBy { it.name }?.dropLast(KEEP_FILES - 1)?.forEach { it.delete() }
        }
        i("app", "start v$version")
    }

    fun resetForTesting() {
        synchronized(lock) { dir = null; file = null; mem.clear() }
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        val t = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val th = Thread.currentThread().name.take(16)
        val line = "$t [$th] $level/$tag: ${scrub(msg.take(2000))}"
        synchronized(lock) {
            mem.addLast(line)
            while (mem.size > MAX_MEM) mem.removeFirst()
            val f = file ?: return
            runCatching { if (f.length() < MAX_FILE_BYTES) f.appendText(line + "\n") }
        }
    }

    private fun scrub(msg: String): String {
        val p = appPath
        return if (!p.isNullOrEmpty()) msg.replace(p, "<app>") else msg
    }

    fun tail(maxLines: Int = 200): String =
        synchronized(lock) { mem.takeLast(maxLines.coerceAtLeast(1)).joinToString("\n") }

    fun fileSize(): Long = runCatching { file?.length() ?: 0 }.getOrDefault(0)

    fun exportText(): String = runCatching { file?.readText()?.ifBlank { tail() } ?: tail() }.getOrDefault(tail())

    /** Best-effort crash breadcrumb; the previous handler still runs afterwards. */
    fun installUncaughtHandler() {
        if (hooked) return
        hooked = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { e("crash", "${t.name}: ${e.javaClass.simpleName}: ${e.message}") }
            prev?.uncaughtException(t, e)
        }
    }
}
