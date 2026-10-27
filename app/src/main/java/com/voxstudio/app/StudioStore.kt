package com.voxstudio.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class StudioStore(val context: Context) {
    init { NativeEngine.appContext=context.applicationContext }
    val prefs = context.getSharedPreferences("studio", Context.MODE_PRIVATE)
    val model get() = Companion.modelFile(context, Companion.selectedModelId(context))
    val modelId get() = Companion.selectedModelId(context)
    /** Expected output rate: 0.5B = 16 kHz, VoxCPM2 = 48 kHz. Projects record their rate. */
    val expectedRate get() = if (modelId == Companion.MODEL_ID_VOXCPM2) 48000 else 16000
    val voices get() = VoiceLibrary(File(context.filesDir,"voices"))
    /** Built-in voices and cloned voices share one id space; built-in ids start with "builtin-". */
    fun findVoice(id: String?): VoiceLibrary.Voice? = if(id?.startsWith(BuiltInVoices.PREFIX)==true) BuiltInVoices.voice(context,id) else voices.find(id)
    /** The chosen voice, else the first built-in voice so every chunk of a project sounds the same. */
    val selectedVoice get() = findVoice(prefs.getString("selectedVoiceId",null)?.takeIf { it.isNotEmpty() }) ?: BuiltInVoices.default(context)
    val reference get() = File(context.filesDir, "voice/reference.wav")
    val projects get() = File(context.filesDir, "projects").also { it.mkdirs() }
    val ready get() = prefs.getBoolean("modelVerified", false) && when (modelId) {
        Companion.MODEL_ID_VOXCPM2 -> model.isFile && model.length() == Companion.MODEL2_BYTES
        else -> model.isFile && model.length() == MODEL_BYTES
    }
    fun history(): List<File> = projects.listFiles()?.filter { File(it,"project.json").isFile && (File(it,"full.wav").isFile || runCatching { metadata(it).optBoolean("studio") }.getOrDefault(false)) }?.sortedByDescending { runCatching { metadata(it).optLong("created",it.lastModified()) }.getOrDefault(it.lastModified()) } ?: emptyList()
    fun metadata(dir: File) = JSONObject(File(dir,"project.json").readText())
    fun saveMetadata(dir: File, data: JSONObject) {
        val temp = File(dir,"project.json.tmp"); temp.writeText(data.toString(2)); check(temp.renameTo(File(dir,"project.json")))
    }
    private fun requireProject(dir: File) {
        require(dir.canonicalFile.parentFile==projects.canonicalFile && dir.name.matches(Regex("[a-f0-9-]{36}")) && File(dir,"project.json").isFile) { "This narration is no longer available." }
    }
    fun renameProject(dir: File, name: String) {
        requireProject(dir)
        val title=name.trim()
        require(title.isNotBlank() && title.length<=80) { "Use a narration name between 1 and 80 characters." }
        saveMetadata(dir,metadata(dir).put("title",title))
    }
    fun deleteProject(dir: File) {
        requireProject(dir)
        check(dir.deleteRecursively()) { "Could not delete this narration." }
    }
    companion object {
        const val MODEL_BYTES = 499832224L
        const val MODEL_SHA = "32fa909cc3c127bfa1003b0863f7b7587bb44e3ae26c6c1cbf224f58d70a9fb3"
        const val MODEL_URL = "https://huggingface.co/bluryar/VoxCPM-GGUF/resolve/7a16c13bad65c5505e38df153d69e02f96eb3960/voxcpm-0.5b-q4_k.gguf?download=true"
        const val MODEL_ID_05B = "voxcpm-0.5b"
        const val MODEL_ID_VOXCPM2 = "voxcpm2"
        /** VoxCPM2 full single-file export (2B, 30 languages incl. Indonesian, 48 kHz).
         * Pinned 2026-10-27: the +AudioVAE-F16 variant decodes faster on phone CPUs.
         * Plain Q4_K alternative (1517531200 bytes, SHA
         * 2cadf3a26b32085366407c1bf8434268d33e78af094dc1fd0b1c25576eb4f15b) is not
         * offered in-app. Split/component files (BaseLM-only, Acoustic-only, e.g.
         * DennisHuang648/VoxCPM2-GGUF) are refused by the GGUF probe at import. */
        const val MODEL2_BYTES = 1592332544L
        const val MODEL2_SHA = "6ab8d1bf70a71cfe846a62aec7c56540655151481676cd571b59914c0c39776b"
        const val MODEL2_URL = "https://huggingface.co/bluryar/VoxCPM-GGUF/resolve/main/voxcpm2-q4_k-audiovae-f16.gguf?download=true"
        fun modelFile(context: Context, id: String): File = File(context.filesDir,
            if (id == MODEL_ID_VOXCPM2) "model/voxcpm2-q4_k.gguf" else "model/voxcpm-0.5b-q4_k.gguf")
        fun selectedModelId(context: Context): String =
            context.getSharedPreferences("studio", Context.MODE_PRIVATE).getString("modelId", MODEL_ID_05B) ?: MODEL_ID_05B
        /** Generic GGUF sanity: magic + size. Strict SHA applies only to the pinned 0.5B. */
        fun looksLikeGguf(file: File, minBytes: Long = 100_000_000L): Boolean {
            if (!file.isFile || file.length() < minBytes) return false
            return runCatching {
                file.inputStream().buffered().use { input ->
                    val magic = ByteArray(4); if (input.read(magic) != 4) return@runCatching false
                    magic.toString(Charsets.US_ASCII) == "GGUF"
                }
            }.getOrDefault(false)
        }
        fun verifySha(file: File, sha: String): Boolean {
            if (!file.isFile) return false
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { stream ->
                    val bytes = ByteArray(256 * 1024)
                    while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == sha
            }.getOrDefault(false)
        }
        fun verify(file: File): Boolean {
            if (file.length() != MODEL_BYTES) return false
            return verifySha(file, MODEL_SHA)
        }
    }
}
object CpuCapabilities {
    init { System.loadLibrary("voxprobe") }
    external fun supportsFast(): Boolean
}
object NativeEngine {
    var appContext: Context?=null
    val available get()=NativeCpuEngine.available
    @Volatile var backendForTesting="auto"
    @Volatile private var gpuFailed=false
    @Volatile private var runningGpu=false
    private var benchmarkSeed=-1
    /** True when this APK ships the separate GPU engine. Checked by file so the main process never loads it. */
    fun gpuPackaged(context: Context)=File(context.applicationInfo.nativeLibraryDir,"libvoxstudio_gpu.so").isFile
    /**
     * GPU is never attempted automatically: no device has passed full end-to-end qualification.
     * It runs only when the user opts in to the experimental setting, and any failure turns the
     * setting off again so later generations (and app updates) never repeat a failed probe.
     */
    fun gpuEnabled(context: Context)=gpuPackaged(context) && context.getSharedPreferences("studio",Context.MODE_PRIVATE).getBoolean("gpuExperimental",false)
    fun synthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String {
        val context=appContext
        AppLog.i("engine", "synthesize model=${File(model).name} steps=$steps threads=$threads textLen=${text.length} ref=${reference != null}")
        val t0=SystemClock.elapsedRealtime()
        val prefs=context?.getSharedPreferences("studio",Context.MODE_PRIVATE)
        val tryGpu=context!=null && when(backendForTesting) { "gpu" -> true; "cpu" -> false; else -> !gpuFailed && gpuEnabled(context) }
        if(tryGpu) {
            runningGpu=true
            StudioJobs.update("Trying experimental GPU · CPU takes over if it fails")
            val error=try { GpuWorkerClient.synthesize(context!!,android.os.Bundle().apply {
                putString("model",model);putString("reference",reference);putString("transcript",transcript)
                putString("text",text);putString("output",output);putInt("steps",steps);putInt("threads",threads);putInt("seed",benchmarkSeed)
            }) } finally { runningGpu=false }
            if(error.isBlank() || backendForTesting=="gpu" || StudioJobs.stop) return error
            gpuFailed=true
            prefs?.edit()?.putBoolean("gpuExperimental",false)?.putString("gpuDisabledReason",error.take(250))?.apply()
            StudioJobs.update("GPU did not work on this phone · continuing on CPU")
            File(output).delete()
        }
        if(backendForTesting=="gpu") return "GPU backend unavailable"
        // The native seed is consumed per call; reapply it so multi-chunk benchmarks stay repeatable.
        if(benchmarkSeed>=0) NativeCpuEngine.setBenchmarkSeedForTesting(benchmarkSeed)
        val error=NativeCpuEngine.synthesize(model,reference,transcript,text,output,steps,threads)
        // Native code keeps the model after success or a user stop and frees it after other failures.
        modelLoaded=error.isBlank() || error.contains("stopped by user")
        AppLog.i("engine", "synthesize done ${SystemClock.elapsedRealtime()-t0}ms ok=${error.isBlank()} reused=$modelLoaded" +
            if (error.isNotBlank()) " err=${error.take(150)}" else "")
        return error
    }
    /** Last native stderr tail (stage/timing lines, no script content). Empty when unavailable. */
    fun lastNativeLog(): String {
        val cpu = runCatching { if (NativeCpuEngine.available) NativeCpuEngine.lastLog() else "" }.getOrDefault("")
        if (cpu.isNotBlank()) return cpu
        return runCatching { if (NativeGpuEngine.available) NativeGpuEngine.lastLog() else "" }.getOrDefault("")
    }
    fun cancel() { if(runningGpu) GpuWorkerClient.cancel() else NativeCpuEngine.cancel() }
    fun setBenchmarkSeedForTesting(seed: Int) {
        benchmarkSeed=seed
        NativeCpuEngine.setBenchmarkSeedForTesting(seed)
    }
    /** Frees the loaded CPU model; the next synthesis reloads it from the verified file. */
    fun release() { if(NativeCpuEngine.available) NativeCpuEngine.release();modelLoaded=false }
    fun releaseForTesting()=release()
    /** True while the CPU model stays resident between chunks (see StudioJobs.scheduleModelRelease). */
    @Volatile var modelLoaded=false; internal set
    val lastRunReusedModel get()=NativeCpuEngine.available && !runningGpu && NativeCpuEngine.lastRunReusedModel()
}
object NativeCpuEngine {
    /** The APK ships two builds of the engine: ARMv8.2 (dot-product, FP16) and baseline ARMv8. */
    val fast: Boolean = CpuCapabilities.supportsFast() && runCatching { System.loadLibrary("voxstudio_cpu_fast") }.isSuccess
    val available: Boolean = fast || runCatching { System.loadLibrary("voxstudio_cpu") }.isSuccess
    /** Two threads measured fastest for the ARMv8.2 engine on big.LITTLE phones; the baseline engine uses four. */
    val defaultThreads get() = if (fast) 2 else 4
    init { android.util.Log.i("HushStudio","Speech engine: ${if (fast) "accelerated" else if (available) "compatible" else "unavailable"}") }
    @Suppress("unused")
    fun onProgress(stage: String, current: Int, total: Int, audioSeconds: Double) {
        StudioJobs.nativeProgress(stage,current,total,audioSeconds)
    }
    fun synthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String {
        require(available) { "The speech engine could not start on this phone's processor." }
        return nativeSynthesize(model,reference,if(reference!=null) SpeechText.referencePrompt(transcript ?: "") else transcript,text,output,steps,threads)
    }
    fun cancel() { if (available) nativeCancel() }
    fun release() { if (available) nativeRelease() }
    fun lastRunReusedModel()=available && nativeLastRunReusedModel()
    fun lastLog(): String = if (available) runCatching { nativeLastLog() }.getOrDefault("") else ""
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
    private external fun nativeRelease()
    private external fun nativeLastRunReusedModel(): Boolean
    private external fun nativeLastLog(): String
    // Used only by the repeatable device benchmark. Normal rendering stays stochastic.
    fun setBenchmarkSeedForTesting(seed: Int) { require(available);nativeBenchmarkSeed(seed) }
    private external fun nativeBenchmarkSeed(seed: Int)
}

object NativeGpuEngine {
    val available: Boolean = try { System.loadLibrary("voxstudio_gpu");true } catch (_: UnsatisfiedLinkError) { false }
    @Suppress("unused")
    fun onProgress(stage: String, current: Int, total: Int, audioSeconds: Double) {
        GpuWorkerService.progress?.invoke(stage,current,total,audioSeconds)
    }
    fun synthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String {
        require(available) { "The speech engine could not start on this phone's processor." }
        return nativeSynthesize(model,reference,if(reference!=null) SpeechText.referencePrompt(transcript ?: "") else transcript,text,output,steps,threads)
    }
    fun cancel() { if (available) nativeCancel() }
    fun release() { if (available) nativeRelease() }
    fun lastLog(): String = if (available) runCatching { nativeLastLog() }.getOrDefault("") else ""
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
    private external fun nativeRelease()
    private external fun nativeLastLog(): String
    // Used only by the repeatable device benchmark. Normal rendering stays stochastic.
    fun setBenchmarkSeedForTesting(seed: Int) { require(available);nativeBenchmarkSeed(seed) }
    private external fun nativeBenchmarkSeed(seed: Int)
}
