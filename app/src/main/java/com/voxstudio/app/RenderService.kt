package com.voxstudio.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

object StudioJobs {
    val generation = GenerationProgress { SystemClock.elapsedRealtime() }
    @Volatile var nativeActive = false; private set
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile var busy = false; private set
    @Volatile var status = "Ready when you are"; private set
    @Volatile var progress = -1; private set
    @Volatile var stop = false; private set
    @Volatile var isTransfer = false; private set
    @Volatile var revision = 0; private set
    @Volatile var lastProject: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var sectionLabel = "Generating audio"
    @Volatile private var pending: ((Context) -> Unit)? = null
    @Synchronized fun submit(context: Context, label: String, transfer: Boolean = false, task: (Context) -> Unit) {
        check(!busy) { "Wait for the current task to finish." }
        generation.reset()
        lastError=null; busy = true; stop = false; isTransfer = transfer; status = label; progress = -1; revision++
        pending = task
        try { context.startForegroundService(Intent(context,RenderService::class.java).putExtra("transfer",transfer)) }
        catch (e: Exception) { pending = null; busy = false; status = e.message ?: "Could not start background task."; revision++; throw e }
    }
    @Synchronized fun takeTask(): ((Context) -> Unit)? = pending.also { pending = null }
    fun cancel() {
        stop = true
        if (nativeActive) NativeEngine.cancel()
        update(if (isTransfer) "Cancelling task…" else "Stop requested · finishing current operation…")
    }
    fun nativeProgress(stage: String, current: Int, total: Int, audioSeconds: Double) {
        generation.stage(stage,current,audioSeconds)
        update("$sectionLabel\n$stage · stage ${generation.snapshot().stageNumber} of 5")
    }
    fun reportFailure(message: String) { lastError=message.take(350);update(lastError!!) }
    fun dismissError() { lastError=null;revision++ }
    fun update(message: String, percent: Int = -1) { status = message; progress = percent; revision++ }
    fun run(service: RenderService, task: (Context) -> Unit) = executor.execute {
        try { task(service.applicationContext) }
        catch (_: CancellationException) { update("Stopped. Completed projects are in your library.") }
        catch (e: Exception) { reportFailure(e.message ?: "The task could not finish.") }
        finally { generation.finish(); busy = false; progress = -1; revision++; service.finishJob() }
    }
    fun checkStop() { if (stop) throw CancellationException() }
    fun download(context: Context) {
        val store = StudioStore(context); store.model.parentFile!!.mkdirs()
        require(store.model.parentFile!!.usableSpace > StudioStore.MODEL_BYTES + 150_000_000) { "Free at least 650 MB of storage first." }
        val temp = File(store.model.parentFile,"download.part")
        val connection = URL(StudioStore.MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000; connection.readTimeout = 30000
        try {
            require(connection.responseCode == 200) { "Download failed (HTTP ${connection.responseCode}). Try importing the model instead." }
            connection.inputStream.buffered().use { input -> temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(256 * 1024); var total = 0L; var previous = -1
                while (true) {
                    checkStop(); val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= StudioStore.MODEL_BYTES) { "Model download has an unexpected size." }
                    output.write(buffer,0,n)
                    val pc = (total * 100 / StudioStore.MODEL_BYTES).toInt()
                    if (pc != previous) { update("Downloading model · $pc%",pc); previous = pc }
                }
            } }
            update("Verifying model integrity…"); checkStop()
            require(StudioStore.verify(temp)) { "Model verification failed. Please retry the download." }
            checkStop(); check(temp.renameTo(store.model)) { "Could not install model." }
            store.prefs.edit().putBoolean("modelVerified",true).apply()
            update("Model installed · ready for offline generation")
        } finally { connection.disconnect(); temp.delete() }
    }
    fun generate(context: Context, script: String, title: String, voiceId: String?, steps: Int) {
        val store = StudioStore(context)
        require(store.ready) { "Install the model in Setup first." }
        val parts = ScriptSections.split(script)
        generation.startProject(parts.size)
        val savedVoice=if(voiceId==null) null else store.voices.find(voiceId) ?: error("Choose an available saved voice.")
        val useVoice=savedVoice!=null
        val transcript=savedVoice?.transcript ?: ""
        val dir = File(store.projects, UUID.randomUUID().toString()).also { it.mkdirs() }
        if (savedVoice!=null) savedVoice.reference.copyTo(File(dir,"reference.wav"),true)
        val json = JSONObject().put("title",title.ifBlank { parts.first().split(Regex("\\s+")).take(7).joinToString(" ") }.take(80)).put("created",System.currentTimeMillis())
            .put("sections",JSONArray(parts)).put("transcript",if (useVoice) transcript else "").put("voice",useVoice).put("steps",steps).put("voiceId",savedVoice?.id ?: "").put("voiceName",savedVoice?.name ?: "Model voice")
        try {
            parts.forEachIndexed { index, text ->
                checkStop(); update("Generating section ${index+1} of ${parts.size}…",index * 100 / parts.size)
                generation.startSection(index+1)
                render(store,dir,index,text,transcript,useVoice,steps)
                generation.sectionDone()
                checkStop()
            }
            update("Assembling your narration…")
            rebuild(dir,parts.size)
            store.saveMetadata(dir,json)
            lastProject = dir.name
            update("Narration ready · ${parts.size} sections saved")
        } finally {
            // Incomplete projects never appear as successful exports or accumulate voice copies.
            if (!File(dir,"project.json").isFile) dir.deleteRecursively()
        }
    }
    fun regenerate(context: Context, dir: File, index: Int) {
        val store = StudioStore(context); require(store.ready) { "Install the model first." }
        val meta = store.metadata(dir); val parts = meta.getJSONArray("sections")
        require(index in 0 until parts.length())
        generation.startProject(1); generation.startSection(1)
        update("Regenerating section ${index+1}…")
        render(store,dir,index,parts.getString(index),meta.getString("transcript"),meta.getBoolean("voice"),meta.getInt("steps"),false)
        generation.sectionDone()
        // Preserve the old full export unless assembly succeeds.
        rebuild(dir,parts.length()); lastProject = dir.name
        update("Section ${index+1} regenerated · export updated")
    }
    private fun render(store: StudioStore, dir: File, index: Int, text: String, transcript: String, voice: Boolean, steps: Int, reuse: Boolean = true) {
        val temp = File(dir,"section-$index.part.wav")
        val start = SystemClock.elapsedRealtime()
        sectionLabel = status.removeSuffix("…")
        val cache=NarrationCache(File(store.context.cacheDir,"narration-cache"))
        val reference=if(voice) File(dir,"reference.wav") else null
        val key=cache.key(text,transcript,reference,"${StudioStore.MODEL_SHA}:${BuildConfig.VERSION_NAME}:engine2:${BuildConfig.MOBILE_FAST}:$steps:${store.prefs.getInt("threads",if(BuildConfig.MOBILE_FAST) 2 else 4)}")
        if(reuse && cache.restore(key,temp)) {
            check(temp.renameTo(File(dir,"section-$index.wav")))
            File(dir,"timing-$index.txt").writeText("cache_hit=true\ngeneration_ms=${SystemClock.elapsedRealtime()-start}\n")
            return
        }
        nativeActive = true
        val error = try {
            NativeEngine.synthesize(store.model.absolutePath,if (voice) File(dir,"reference.wav").absolutePath else null,
                if (voice) transcript else null,text,temp.absolutePath,steps,store.prefs.getInt("threads",if(BuildConfig.MOBILE_FAST) 2 else 4).coerceIn(1,Runtime.getRuntime().availableProcessors().coerceAtMost(4)))
        } finally { nativeActive=false }
        val nativeMs=SystemClock.elapsedRealtime()-start
        checkStop()
        require(error.isBlank()) { error }
        val wave = AudioFiles.read(temp)
        require(wave.samples.isNotEmpty() && wave.rate == 16000) { "Model returned invalid audio." }
        check(temp.renameTo(File(dir,"section-$index.wav"))) { "Could not save section." }
        cache.save(key,File(dir,"section-$index.wav"))
        val stageTimes = generation.snapshot().stageTimes.entries.joinToString("\n") { "${it.key}=${it.value}" }
        File(dir,"timing-$index.txt").writeText("generation_ms=${SystemClock.elapsedRealtime()-start}\naudio_seconds=${wave.seconds}\nnative_rate=16000\nnative_ms=$nativeMs\nvalidation_ms=${SystemClock.elapsedRealtime()-start-nativeMs}\n$stageTimes\n")
    }
    private fun rebuild(dir: File, count: Int) {
        val temp = File(dir,"full.part.wav")
        AudioFiles.join((0 until count).map { File(dir,"section-$it.wav") },temp)
        check(temp.renameTo(File(dir,"full.wav"))) { "Could not assemble export." }
    }
}

class RenderService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification())
            handler.postDelayed(this,2000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("render","Narration progress",NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 35 && intent?.getBooleanExtra("transfer",false) != true) startForeground(7,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(7,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(7,notification())
        val task = StudioJobs.takeTask()
        if (task == null) { stopSelf(); return START_NOT_STICKY }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VoxStudio:render").apply { acquire(2 * 60 * 60 * 1000L) }
        handler.post(refresh); StudioJobs.run(this,task)
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"render").setContentTitle("Vox Studio").setContentText(StudioJobs.status)
            .setSmallIcon(com.voxstudio.app.R.drawable.ic_vox).setContentIntent(open).setOngoing(true)
            .setProgress(100,StudioJobs.progress.coerceAtLeast(0),StudioJobs.progress < 0).build()
    }
    fun finishJob() { handler.post { handler.removeCallbacks(refresh); wake?.let { if (it.isHeld) it.release() }; wake = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
    override fun onDestroy() { handler.removeCallbacks(refresh); wake?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { StudioJobs.cancel(); stopSelf() }
}
