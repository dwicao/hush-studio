package com.voxstudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class StudioStore(val context: Context) {
    init { NativeEngine.appContext=context.applicationContext }
    val prefs = context.getSharedPreferences("studio", Context.MODE_PRIVATE)
    val model get() = File(context.filesDir, "model/voxcpm-0.5b-q4_k.gguf")
    val voices get() = VoiceLibrary(File(context.filesDir,"voices"))
    /** Built-in voices and cloned voices share one id space; built-in ids start with "builtin-". */
    fun findVoice(id: String?): VoiceLibrary.Voice? = if(id?.startsWith(BuiltInVoices.PREFIX)==true) BuiltInVoices.voice(context,id) else voices.find(id)
    /** The chosen voice, else the first built-in voice so every chunk of a project sounds the same. */
    val selectedVoice get() = findVoice(prefs.getString("selectedVoiceId",null)?.takeIf { it.isNotEmpty() }) ?: BuiltInVoices.default(context)
    val reference get() = File(context.filesDir, "voice/reference.wav")
    val projects get() = File(context.filesDir, "projects").also { it.mkdirs() }
    val ready get() = model.isFile && model.length() == MODEL_BYTES && prefs.getBoolean("modelVerified", false)
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
        fun verify(file: File): Boolean {
            if (file.length() != MODEL_BYTES) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val bytes = ByteArray(256 * 1024)
                while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes,0,n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA
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
        return error
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
    val available: Boolean = !BuildConfig.MOBILE_FAST || CpuCapabilities.supportsFast()
    init { if (available) System.loadLibrary("voxstudio_cpu") }
    @Suppress("unused")
    fun onProgress(stage: String, current: Int, total: Int, audioSeconds: Double) {
        StudioJobs.nativeProgress(stage,current,total,audioSeconds)
    }
    fun synthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String {
        require(available) { "This device needs the compatibility APK; its CPU lacks FP16/dot-product instructions." }
        return nativeSynthesize(model,reference,if(reference!=null) SpeechText.referencePrompt(transcript ?: "") else transcript,text,output,steps,threads)
    }
    fun cancel() { if (available) nativeCancel() }
    fun release() { if (available) nativeRelease() }
    fun lastRunReusedModel()=available && nativeLastRunReusedModel()
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
    private external fun nativeRelease()
    private external fun nativeLastRunReusedModel(): Boolean
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
        require(available) { "This device needs the compatibility APK; its CPU lacks FP16/dot-product instructions." }
        return nativeSynthesize(model,reference,if(reference!=null) SpeechText.referencePrompt(transcript ?: "") else transcript,text,output,steps,threads)
    }
    fun cancel() { if (available) nativeCancel() }
    fun release() { if (available) nativeRelease() }
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
    private external fun nativeRelease()
    // Used only by the repeatable device benchmark. Normal rendering stays stochastic.
    fun setBenchmarkSeedForTesting(seed: Int) { require(available);nativeBenchmarkSeed(seed) }
    private external fun nativeBenchmarkSeed(seed: Int)
}
