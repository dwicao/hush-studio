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
    val selectedVoice get() = voices.find(prefs.getString("selectedVoiceId",null))
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
    fun synthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String {
        val prefs=appContext?.getSharedPreferences("studio",Context.MODE_PRIVATE)
        val deviceBuild=android.os.Build.FINGERPRINT+BuildConfig.VERSION_NAME
        val disabled=prefs?.getString("gpuDisabledBuild",null)==deviceBuild
        if(backendForTesting!="cpu" && (!gpuFailed && !disabled || backendForTesting=="gpu") && appContext!=null) {
            runningGpu=true
            val error=try { GpuWorkerClient.synthesize(appContext!!,android.os.Bundle().apply {
                putString("model",model);putString("reference",reference);putString("transcript",transcript)
                putString("text",text);putString("output",output);putInt("steps",steps);putInt("threads",threads);putInt("seed",benchmarkSeed)
            }) } finally { runningGpu=false }
            if(error.isBlank() || backendForTesting=="gpu" || StudioJobs.stop) return error
            gpuFailed=true
            prefs?.edit()?.putString("gpuDisabledBuild",deviceBuild)?.putString("gpuDisabledReason",error.take(250))?.apply()
            StudioJobs.update("GPU unavailable for this model · using CPU")
            java.io.File(output).delete()
        }
        if(backendForTesting=="gpu") return "GPU backend unavailable"
        return NativeCpuEngine.synthesize(model,reference,transcript,text,output,steps,threads)
    }
    fun cancel() { if(runningGpu) GpuWorkerClient.cancel() else NativeCpuEngine.cancel() }
    fun setBenchmarkSeedForTesting(seed: Int) {
        benchmarkSeed=seed
        NativeCpuEngine.setBenchmarkSeedForTesting(seed)

    }
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
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
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
    private external fun nativeSynthesize(model: String, reference: String?, transcript: String?, text: String, output: String, steps: Int, threads: Int): String
    private external fun nativeCancel()
    // Used only by the repeatable device benchmark. Normal rendering stays stochastic.
    fun setBenchmarkSeedForTesting(seed: Int) { require(available);nativeBenchmarkSeed(seed) }
    private external fun nativeBenchmarkSeed(seed: Int)
}
