package com.voxstudio.app

import android.app.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import java.io.File

/** Paragraph-oriented studio with selected-chunk actions and a pinned transport. */
class ChunkStudioDialog(private val activity: Activity,private val dir: File,
    private val startTask: (String,(android.content.Context)->Unit)->Unit,
    private val export: ()->Unit): Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar) {
    private val ink=Color.rgb(20,20,22);private val muted=Color.rgb(110,113,120)
    private val blue=Color.rgb(221,235,255);private val border=Color.rgb(231,232,236)
    private val store=StudioStore(activity)
    private val handler=Handler(Looper.getMainLooper())
    private var selected=0;private var loaded=-1;private var playingAll=false;private var revision=""
    private lateinit var document: LinearLayout;private lateinit var scroll: ScrollView
    private lateinit var edit: Button
    private lateinit var action: Button;private lateinit var play: Button;private lateinit var all: Button;private lateinit var exportButton: Button
    private lateinit var generationProgress: ProgressBar
    private lateinit var state: TextView;private lateinit var position: TextView;private lateinit var seek: SeekBar;private lateinit var stop: Button
    private var dragging=false
    private val audio=AudioPlayback(activity) { if(::play.isInitialized) refreshTransport() }
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun shape(color: Int,radius: Int=12)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun text(value: String,size: Float=14f,color: Int=ink)=TextView(activity).apply { this.text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f) }
    private fun button(value: String,dark: Boolean=false,click: ()->Unit)=Button(activity).apply {
        this.text=value;isAllCaps=false;textSize=13f;minWidth=0;minimumWidth=0
        setTextColor(if(dark) Color.WHITE else ink);background=shape(if(dark) ink else Color.rgb(246,246,248))
        setPadding(dp(8),0,dp(8),0);setOnClickListener { guard(click) }
    }
    private fun row(parent: LinearLayout)=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;parent.addView(this,LinearLayout.LayoutParams(-1,-2)) }
    private fun add(parent: LinearLayout,view: View,weight: Float=1f,height: Int=46) { parent.addView(view,LinearLayout.LayoutParams(0,dp(height),weight).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
    private fun gap(parent: LinearLayout,n: Int=12) { parent.addView(Space(activity),LinearLayout.LayoutParams(1,dp(n))) }
    private fun meta()=store.metadata(dir)
    private fun parts()=meta().getJSONArray("sections")
    private fun file(index: Int)=File(dir,"section-$index.wav")
    private fun guard(task: ()->Unit) { try { task() } catch(e: Exception) { AlertDialog.Builder(activity).setTitle("Couldn't continue").setMessage(e.message).setPositiveButton("Close",null).show() } }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE) }
        setContentView(root)
        window?.apply { statusBarColor=Color.WHITE;navigationBarColor=Color.WHITE;decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
        val header=row(root).apply { setPadding(dp(12),dp(8),dp(12),dp(8)) }
        header.addView(button("‹") { dismiss() },LinearLayout.LayoutParams(dp(42),dp(46)))
        header.addView(text(meta().getString("title"),17f).apply { maxLines=2;setPadding(dp(8),0,dp(8),0) },LinearLayout.LayoutParams(0,-2,1f))
        exportButton=button("Export",true) { require(File(dir,"full.wav").isFile) { "Generate all chunks before exporting." };pause();export() }
        header.addView(exportButton,LinearLayout.LayoutParams(dp(80),dp(46)))
        val tools=row(root).apply { setPadding(dp(12),0,dp(12),dp(6)) }
        action=button("Generate",true) { generate(listOf(selected),file(selected).isFile) };add(tools,action,1.35f)
        edit=button("Edit text") { editChunk() };add(tools,edit)
        all=button("Generate all") { generate((0 until parts().length()).filter { !file(it).isFile },false) };add(tools,all,1.3f)
        scroll=ScrollView(activity).apply { isFillViewport=true }
        document=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(30)) }
        scroll.addView(document);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(12));background=shape(Color.rgb(249,250,252),0) }
        root.addView(footer)
        generationProgress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply { max=5;progressTintList=android.content.res.ColorStateList.valueOf(ink) }
        footer.addView(generationProgress,LinearLayout.LayoutParams(-1,dp(4)))
        state=text("",12f,muted);footer.addView(state)
        seek=SeekBar(activity).apply { max=1000;progressTintList=android.content.res.ColorStateList.valueOf(ink);thumbTintList=android.content.res.ColorStateList.valueOf(ink);contentDescription="Chunk playback position" }
        footer.addView(seek,LinearLayout.LayoutParams(-1,dp(34)))
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar?) { dragging=true }
            override fun onStopTrackingTouch(b: SeekBar?) { audio.seek((audio.duration.toLong()*seek.progress/1000).toInt());dragging=false }
            override fun onProgressChanged(b: SeekBar?,p: Int,user: Boolean) { if(user && !dragging)audio.seek((audio.duration.toLong()*p/1000).toInt()) }
        })
        val transport=row(footer)
        position=text("0:00 / 0:00",11f,muted);transport.addView(position,LinearLayout.LayoutParams(0,-2,1.3f))
        add(transport,button("│‹") { select((selected-1).coerceAtLeast(0)) },.65f)
        play=button("▶",true) { if(loaded!=selected) load(true) else { playingAll=!audio.playing;audio.toggle() } };add(transport,play,.8f,48)
        add(transport,button("›│") { select((selected+1).coerceAtMost(parts().length()-1)) },.65f)
        add(transport,button("1×") { val speeds=floatArrayOf(.75f,1f,1.25f,1.5f);val next=speeds[(speeds.indexOfFirst { kotlin.math.abs(it-audio.speed)<.01f }+1)%speeds.size];audio.changeSpeed(next);(transport.getChildAt(4) as Button).text="${next}×" },.8f)
        stop=button("Stop generation") { StudioJobs.cancel() };footer.addView(stop,LinearLayout.LayoutParams(-1,dp(42)))
        renderDocument();handler.post(tick)
    }
    private fun TextView.assign(value: String) { if(text.toString()!=value)text=value }
    private fun time(ms: Int)="%d:%02d".format(ms/60000,ms/1000%60)
    private fun select(index: Int) { audio.pause();playingAll=false;selected=index;loaded=-1;renderDocument();refreshTransport() }
    private fun load(auto: Boolean) { require(file(selected).isFile) { "Generate this chunk first." };loaded=selected;playingAll=auto;audio.load(file(selected),auto) }
    private fun refreshTransport() {
        val ready=file(selected).isFile
        play.isEnabled=ready && !audio.loading;play.assign(if(audio.playing) "Ⅱ" else "▶")
        play.contentDescription=if(audio.playing) "Pause playback" else "Play selected chunk"
        if(!dragging)seek.progress=if(loaded==selected && audio.duration>0) (audio.position.toLong()*1000/audio.duration).toInt() else 0
        seek.isEnabled=ready && loaded==selected && !audio.loading
        position.assign(if(loaded==selected) "${time(audio.position)} / ${time(audio.duration)}" else "0:00 / 0:00")
        generationProgress.visibility=if(StudioJobs.busy) View.VISIBLE else View.GONE
        generationProgress.progress=StudioJobs.generation.snapshot().stageNumber.coerceIn(0,5)
        state.assign(if(StudioJobs.busy) StudioJobs.status+" · "+StudioJobs.generation.snapshot().elapsed else StudioJobs.lastError?.let { "Couldn't generate: $it" } ?: audio.error ?: "Chunk ${selected+1} of ${parts().length()} · ${if(ready) "Ready to play" else "Not generated"}")
        stop.visibility=if(StudioJobs.busy) View.VISIBLE else View.GONE
        edit.isEnabled=!StudioJobs.busy
        action.isEnabled=!StudioJobs.busy;all.isEnabled=!StudioJobs.busy && (0 until parts().length()).any { !file(it).isFile }
        exportButton.isEnabled=!StudioJobs.busy && File(dir,"full.wav").isFile
        for(control in listOf(exportButton,action,edit,all,play))control.alpha=if(control.isEnabled) 1f else .35f
    }
    private fun renderDocument() {
        val y=scroll.scrollY;document.removeAllViews();val m=meta();val chunks=m.getJSONArray("sections")
        document.addView(text("${m.optString("voiceName","Default voice")} · ${chunks.length()} chunks",12f,muted));gap(document,18)
        for(i in 0 until chunks.length()) {
            val block=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,dp(8));isSelected=i==selected;contentDescription="Chunk ${i+1}";setOnClickListener { select(i) } }
            val avatar=text(m.optString("voiceName","V").take(1).uppercase(),12f,Color.WHITE).apply { gravity=Gravity.CENTER;background=shape(ink,30) }
            block.addView(avatar,LinearLayout.LayoutParams(dp(25),dp(25)).apply { setMargins(0,dp(6),dp(10),0) })
            val line=View(activity).apply { setBackgroundColor(if(i==selected) ink else border) };block.addView(line,LinearLayout.LayoutParams(dp(2),-1).apply { setMargins(0,0,dp(10),0) })
            val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            val words=text(chunks.getString(i),17f).apply { setPadding(dp(7),dp(6),dp(7),dp(6));background=shape(if(i==selected) blue else if(file(i).isFile) Color.rgb(244,248,255) else Color.WHITE,5) }
            body.addView(words)
            val status=if(file(i).isFile) "✓ Generated · ${time(runCatching { AudioPeaks.durationMs(file(i)).toInt() }.getOrDefault(0))}" else "○ Not generated"
            body.addView(text("${i+1}   $status",11f,muted).apply { setPadding(dp(7),dp(7),0,0) })
            block.addView(body,LinearLayout.LayoutParams(0,-2,1f));document.addView(block);gap(document,10)
        }
        action.text=if(file(selected).isFile) "↻ Regenerate" else "Generate chunk"
        scroll.post { scroll.scrollTo(0,y) }
    }
    private fun generate(indices: List<Int>,fresh: Boolean) {
        require(!StudioJobs.busy) { "Wait for the current task." }
        require(indices.isNotEmpty()) { "All chunks are generated. Select a chunk to regenerate it." }
        audio.release();loaded=-1;playingAll=false
        startTask(if(fresh) "Regenerating selected chunk…" else "Generating chunks…") { StudioJobs.generateChunks(it,dir,indices,fresh) }
        refreshTransport()
    }
    private fun editChunk() {
        require(!StudioJobs.busy) { "Wait for generation to finish." }
        val editContext=ContextThemeWrapper(activity,android.R.style.Theme_Material_Light_Dialog_Alert)
        val field=EditText(editContext).apply { setText(parts().getString(selected));minLines=4;maxLines=8;filters=arrayOf(android.text.InputFilter.LengthFilter(220));setTextColor(ink) }
        val dialog=AlertDialog.Builder(editContext).setTitle("Edit chunk ${selected+1}").setMessage("Changing these words requires generating this chunk again.").setView(field)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { guard { ChunkProjects.edit(activity,dir,selected,field.text.toString());audio.release();loaded=-1;renderDocument();refreshTransport();dialog.dismiss() } } };dialog.show()
    }
    private val tick=object: Runnable { override fun run() {
        val nextRevision=File(dir,"project.json").lastModified().toString()+ (0 until parts().length()).joinToString { "${file(it).lastModified()}:${file(it).length()}" }
        if(revision!=nextRevision) { revision=nextRevision;renderDocument() }
        if(playingAll && loaded==selected && !audio.loading && !audio.playing && audio.duration>0 && audio.position>=audio.duration-40) {
            if(selected+1<parts().length() && file(selected+1).isFile) { selected++;renderDocument();load(true) } else playingAll=false
        }
        if(!StudioJobs.busy && loaded!=selected && file(selected).isFile)load(false)
        refreshTransport();handler.postDelayed(this,250)
    } }
    fun pause() { handler.removeCallbacks(tick);audio.pause();playingAll=false }
    fun resume() { handler.removeCallbacks(tick);handler.post(tick) }
    override fun onStart() { super.onStart();window?.setLayout(-1,-1) }
    override fun onStop() { handler.removeCallbacks(tick);audio.release();super.onStop() }
}
