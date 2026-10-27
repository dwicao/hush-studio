package com.voxstudio.app

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** File recognition runs locally and releases the model before TTS starts. */
object SpeechTranscriber {
    const val MODEL_SHA = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    const val MODEL_LANG_EN = "en"
    const val MODEL_LANG_ID = "id"
    private const val ROOT_EN = "vosk-model-small-en-us-0.15"
    // No official small Indonesian Vosk model is pinned yet. When one is available,
    // bundle it as assets/speech-model-id.zip and fill ID_MODEL_SHA/ID_ROOT below.
    // Until then Indonesian samples fall back to manual transcript entry.
    const val ID_MODEL_SHA = ""
    private const val ROOT_ID = "vosk-model-small-id-0.22"
    fun selectedLang(context: android.content.Context): String =
        context.getSharedPreferences("studio", android.content.Context.MODE_PRIVATE).getString("asrLang", MODEL_LANG_EN) ?: MODEL_LANG_EN
    fun setLang(context: android.content.Context, lang: String) {
        context.getSharedPreferences("studio", android.content.Context.MODE_PRIVATE).edit().putString("asrLang", lang).apply()
    }
    /** True when the selected ASR model is actually bundled and verifiable. */
    fun isLangReady(context: android.content.Context): Boolean {
        if (selectedLang(context) == MODEL_LANG_ID) {
            if (ID_MODEL_SHA.isBlank()) return false
            return runCatching {
                context.assets.open("speech-model-id.zip").use { it.close() }
                true
            }.getOrDefault(false)
        }
        return true
    }
    private fun model(context: android.content.Context): File {
        val lang = selectedLang(context)
        val rootName = if (lang == MODEL_LANG_ID) ROOT_ID else ROOT_EN
        val asset = if (lang == MODEL_LANG_ID) "speech-model-id.zip" else "speech-model.zip"
        val sha = if (lang == MODEL_LANG_ID) ID_MODEL_SHA else MODEL_SHA
        require(!(lang == MODEL_LANG_ID && sha.isBlank())) { "Model suara Indonesia belum tersedia. Ketik manual transkrip sampel Anda." }
        val destination=File(context.filesDir,"speech-model-$lang")
        // One-time migration: 0.1.16 used "speech-model" for English.
        if (lang == MODEL_LANG_EN && !File(destination,".verified").isFile) {
            runCatching {
                val legacy = File(context.filesDir, "speech-model")
                if (File(legacy, ".verified").takeIf { it.isFile }?.readText() == MODEL_SHA) legacy.renameTo(destination)
            }
        }
        if(File(destination,".verified").takeIf { it.isFile }?.readText()==sha) return destination
        require(context.filesDir.usableSpace>100_000_000) { "Siapkan 100 MB untuk pengenalan suara offline." }
        StudioJobs.update("Menyiapkan pengenalan suara offline…")
        val temp=File(context.filesDir,"speech-model-$lang.install")
        temp.deleteRecursively(); temp.mkdirs()
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open(asset).use { source ->
                DigestInputStream(source,digest).use { hashed ->
                    ZipInputStream(hashed).use { zip ->
                        var total=0L; var entries=0
                        while(true) {
                            StudioJobs.checkStop()
                            val entry=zip.nextEntry ?: break
                            require(++entries<=100 && entry.name.startsWith("$rootName/")) { "Arsip model suara tidak valid." }
                            val name=entry.name.removePrefix("$rootName/")
                            if(name.isEmpty()) continue
                            val file=File(temp,name)
                            require(file.canonicalPath.startsWith(temp.canonicalPath+File.separator)) { "Path model suara tidak valid." }
                            if(entry.isDirectory) file.mkdirs() else {
                                file.parentFile!!.mkdirs()
                                file.outputStream().buffered().use { output ->
                                    val buffer=ByteArray(65536)
                                    while(true) {
                                        StudioJobs.checkStop()
                                        val n=zip.read(buffer); if(n<0) break
                                        total+=n; require(total<=100_000_000) { "Model suara terlalu besar." }
                                        output.write(buffer,0,n)
                                    }
                                }
                            }
                            zip.closeEntry()
                        }
                        // ZIP readers may stop before the central directory has been hashed.
                        val buffer=ByteArray(65536)
                        while(hashed.read(buffer)>=0) StudioJobs.checkStop()
                    }
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) }==sha) { "Verifikasi model suara gagal." }
            require(File(temp,"am/final.mdl").isFile && File(temp,"graph/HCLr.fst").isFile) { "Model suara tidak lengkap." }
            File(temp,".verified").writeText(sha)
            destination.deleteRecursively()
            check(temp.renameTo(destination)) { "Tidak dapat memasang model suara offline." }
            return destination
        } finally { temp.deleteRecursively() }
    }
    fun transcribe(context: Context, reference: File): String {
        val audio=AudioFiles.read(reference,20)
        require(audio.channels==1 && audio.rate==16000) { "Simpan referensi suara dulu sebelum mengekstrak kata-katanya." }
        val directory=model(context)
        StudioJobs.checkStop(); StudioJobs.update("Mengekstrak kata offline…")
        val words=mutableListOf<String>()
        Model(directory.absolutePath).use { model ->
            Recognizer(model,16000f).use { recognizer ->
                var at=0
                while(at<audio.samples.size) {
                    StudioJobs.checkStop()
                    val end=minOf(at+4000,audio.samples.size)
                    val chunk=audio.samples.copyOfRange(at,end)
                    if(recognizer.acceptWaveForm(chunk,chunk.size)) {
                        JSONObject(recognizer.result).optString("text").trim().takeIf { it.isNotEmpty() }?.let(words::add)
                    }
                    at=end
                    StudioJobs.update("Mengekstrak kata offline · ${at/16000}s / ${audio.samples.size/16000}s",at*100/audio.samples.size)
                }
                JSONObject(recognizer.finalResult).optString("text").trim().takeIf { it.isNotEmpty() }?.let(words::add)
            }
        }
        StudioJobs.checkStop()
        val text=words.joinToString(" ").trim()
        require(text.isNotBlank()) { "Tidak ada ucapan jelas yang terdeteksi. Coba klip yang lebih bersih atau ketik manual kata-katanya." }
        require(text.length<=600) { "Transkrip terlalu panjang. Pilih klip suara yang lebih pendek." }
        return text
    }
}
