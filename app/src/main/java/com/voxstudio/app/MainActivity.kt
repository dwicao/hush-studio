package com.voxstudio.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.net.Uri
import android.os.*
import android.text.*
import android.view.*
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity : Activity() {
    private val bg = Color.rgb(16,19,17)
    private val panel = Color.rgb(27,32,28)
    private val line = Color.rgb(49,57,50)
    private val ink = Color.rgb(241,241,229)
    private val muted = Color.rgb(157,170,154)
    private val orange = Color.rgb(242,166,90)
    private lateinit var store: StudioStore
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var tab = "Create"
    private var voiceEditor=true
    private var voiceNotice=""
    private var cloneVoiceAction: (() -> Unit)?=null
    private var generateSpeechAction: (() -> Unit)?=null
    private var footerReady: (() -> Boolean)?=null
    private var footerMessage: (() -> String)?=null
    private var footerButton: Button?=null
    private var footerHint: TextView?=null
    private var pageScroll: ScrollView?=null
    private var drawnTab=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private var libraryQuery=""
    private var advancedSettings=false
    private val handler = Handler(Looper.getMainLooper())
    private var seenRevision = -1
    private var referenceStamp = 0L
    private var observedBusy = false
    private var jobText: TextView? = null
    private var timingText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var stopButton: Button? = null
    private var exportProject: File? = null
    private var playerDialog: StudioPlayerDialog? = null
    private var recorder: VoiceRecorder? = null
    private var recordLabel: TextView? = null
    private val io = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            if (seenRevision != StudioJobs.revision) {
                val changed = observedBusy != StudioJobs.busy
                observedBusy = StudioJobs.busy; seenRevision = StudioJobs.revision
                if (changed) draw() else updateProgress()
            }
            if (StudioJobs.busy) updateProgress()
            if (referenceStamp != store.reference.lastModified()) { referenceStamp = store.reference.lastModified(); draw() }
            val recordingStatus = when {
                recorder?.running == true -> "Recording · ${recorder!!.seconds}s / 20s"
                recorder?.active == true -> "Processing sample…"
                store.reference.isFile -> "Reference saved on this device"
                else -> "Tap record for a 3–20 second sample"
            }
            if(recordLabel?.text?.toString()!=recordingStatus) recordLabel?.text=recordingStatus
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = StudioStore(applicationContext)
        referenceStamp=store.reference.lastModified()
        if (!StudioJobs.busy) {
            File(store.model.parentFile,"download.part").delete()
            File(store.model.parentFile,"import.part").delete()
            store.projects.listFiles()?.filter { !File(it,"project.json").isFile }?.forEach { it.deleteRecursively() }
        }
        tab = savedInstanceState?.getString("tab") ?: "Create"
        if(tab=="Setup") tab="Settings"
        libraryQuery=savedInstanceState?.getString("libraryQuery") ?: ""
        listOf("Create","Voices","Library","Settings").forEach { key -> scrollPositions[key]=savedInstanceState?.getInt("scroll$key") ?: 0 }
        voiceEditor = savedInstanceState?.getBoolean("voiceEditor") ?: store.voices.list().isEmpty()
        exportProject = savedInstanceState?.getString("exportProject")?.let { File(store.projects,it) }
        window.statusBarColor = bg; window.navigationBarColor = bg
        draw()
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); playerDialog?.pause(); recorder?.stop(); super.onPause() }
    override fun onDestroy() { stopPlayback(); io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { pageScroll?.let { scrollPositions[drawnTab]=it.scrollY };scrollPositions.forEach { (key,value) -> outState.putInt("scroll$key",value) };outState.putString("libraryQuery",libraryQuery);outState.putString("tab",tab); outState.putBoolean("voiceEditor",voiceEditor); outState.putString("exportProject",exportProject?.name); super.onSaveInstanceState(outState) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun shape(color: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border != null) setStroke(dp(1),border)
    }
    private fun label(text: String, size: Float = 14f, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(),1f)
    }
    private fun gap(parent: LinearLayout, n: Int = 12) { parent.addView(Space(this),LinearLayout.LayoutParams(1,dp(n))) }
    private fun column(parent: LinearLayout, card: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (card) { background = shape(panel,16,line); setPadding(dp(16),dp(16),dp(16),dp(16)) }
        parent.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun row(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parent.addView(this,LinearLayout.LayoutParams(-1,-2))
    }
    private fun button(text: String, primary: Boolean = false, enabled: Boolean = !StudioJobs.busy, click: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f; minHeight = dp(48); minimumHeight = dp(48)
        typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(if (primary) bg else ink)
        background = shape(if (primary) orange else Color.rgb(39,47,40),12)
        setPadding(dp(14),dp(6),dp(14),dp(6)); isEnabled = enabled; alpha = if (enabled) 1f else .4f
        setOnClickListener { safely(click) }
    }
    private fun fullButton(parent: LinearLayout, text: String, primary: Boolean = false, enabled: Boolean = !StudioJobs.busy, click: () -> Unit) {
        parent.addView(button(text,primary,enabled,click),LinearLayout.LayoutParams(-1,dp(50)))
    }
    private fun field(parent: LinearLayout, hintText: String, value: String, lines: Int = 1, key: String? = null): EditText = EditText(this).apply {
        hint = hintText; setText(value); setTextColor(ink); setHintTextColor(muted); textSize = 16f
        gravity = Gravity.TOP or Gravity.START; minLines = lines; maxLines = lines
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (lines > 1) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if(lines>1) {
            setLines(lines); setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            var lastY=0f; var downY=0f
            val slop=ViewConfiguration.get(context).scaledTouchSlop
            setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY=event.y; downY=event.y
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val distance=lastY-event.y
                        if(abs(event.y-downY)>slop && distance!=0f) {
                            val direction=if(distance>0) 1 else -1
                            view.parent.requestDisallowInterceptTouchEvent(view.canScrollVertically(direction))
                        }
                        lastY=event.y
                    }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        background = shape(Color.rgb(20,25,21),12,line); setPadding(dp(14),dp(14),dp(14),dp(14))
        isEnabled = !StudioJobs.busy && recorder?.active!=true; contentDescription=hintText; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        parent.addView(this,LinearLayout.LayoutParams(-1,-2))
        if (key != null) addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { store.prefs.edit().putString(key,s.toString()).apply();updateFooter() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun hideKeyboard() {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken,0);it.clearFocus() }
    }
    private fun navigate(destination: String) {
        if(recorder?.active==true) { toast("Finish your recording before switching screens.");return }
        stopPlayback();hideKeyboard();tab=destination;draw()
    }
    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if(recorder?.active==true) { toast("Finish your recording before leaving.");return }
        if(tab=="Voices" && voiceEditor && store.voices.list().isNotEmpty()) { voiceEditor=false;draw() }
        else if(tab!="Create") navigate("Create") else super.onBackPressed()
    }
    private fun updateFooter() {
        val blocked=StudioJobs.busy || recorder?.active==true
        val ready=footerReady?.invoke() ?: true
        footerButton?.let {
            it.isEnabled=!blocked && ready;it.alpha=if(it.isEnabled) 1f else .4f
            val title=if(StudioJobs.busy) "Working…" else if(cloneVoiceAction!=null) "Clone voice" else if(!store.ready) "Set up to generate" else "Generate narration"
            if(it.text.toString()!=title) it.text=title
        }
        val hint=if(StudioJobs.busy) "You can browse while this task runs." else footerMessage?.invoke() ?: ""
        footerHint?.let { if(it.text.toString()!=hint) it.text=hint }
    }
    private fun pageTitle(title: String,subtitle: String) {
        content.addView(label(title,27f,ink,true));gap(content,6)
        content.addView(label(subtitle,13f,muted));gap(content,18)
    }
    private fun draw() {
        pageScroll?.let { scrollPositions[drawnTab]=it.scrollY }
        cloneVoiceAction=null;generateSpeechAction=null;footerReady=null;footerMessage=null;footerButton=null;footerHint=null
        jobText=null;timingText=null;progressBar=null;stopButton=null;recordLabel=null
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg) }
        var navigationView: View?=null
        root.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0,bars.top,0,bars.bottom)
                navigationView?.visibility=if(insets.isVisible(WindowInsets.Type.ime())) View.GONE else View.VISIBLE
            } else view.setPadding(0,insets.systemWindowInsetTop,0,insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(10)) }
        header.addView(label("▥  VOX STUDIO",15f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(label(if(store.ready) "● Offline ready" else "Set up offline",11f,if(store.ready) muted else orange,true).apply {
            background=shape(panel,20);setPadding(dp(12),dp(10),dp(12),dp(10));contentDescription="Open Settings"
            isFocusable=true;setOnClickListener { navigate("Settings") }
        })
        root.addView(header)
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        pageScroll=scroll;drawnTab=tab
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(22)) }
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(StudioJobs.busy) progressCard()
        else StudioJobs.lastError?.let { message ->
            val box=column(content,true)
            box.addView(label("Couldn't finish",15f,Color.rgb(245,162,142),true));gap(box,6)
            box.addView(label(message,13f,muted));gap(box,10)
            fullButton(box,"Dismiss",enabled=true) { StudioJobs.dismissError();draw() };gap(content,16)
        }
        when(tab) { "Create" -> createScreen();"Voices" -> voiceScreen();"Library" -> libraryScreen();else -> setupScreen() }
        (cloneVoiceAction ?: generateSpeechAction)?.let { action ->
            val footer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(10));background=shape(bg,0,line) }
            footerHint=label("",11f,muted).also { footer.addView(it) };gap(footer,6)
            footerButton=button("",true) { hideKeyboard();action() }.also { footer.addView(it,LinearLayout.LayoutParams(-1,dp(50))) }
            root.addView(footer);updateFooter()
        }
        val navigation=row(root).apply { setPadding(dp(10),dp(6),dp(10),dp(8));background=shape(bg,0,line) }
        navigationView=navigation
        // Some resize-mode windows consume IME insets before they reach our root.
        // Observe the visible window as well so typing always gets the tab space.
        val visibleWindow=Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(visibleWindow)
            val keyboardVisible=(Build.VERSION.SDK_INT>=30 && root.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true) ||
                window.decorView.height-visibleWindow.bottom>dp(180)
            val visibility=if(keyboardVisible) View.GONE else View.VISIBLE
            if(navigation.visibility!=visibility) navigation.visibility=visibility
        }
        listOf("Create" to "✦","Voices" to "◉","Library" to "≡","Settings" to "⚙").forEach { (item,icon) ->
            val nav=label("$icon\n$item",11f,if(tab==item) orange else muted,tab==item).apply {
                gravity=Gravity.CENTER;background=shape(if(tab==item) panel else bg,12);setPadding(0,dp(6),0,dp(6));isFocusable=true
                contentDescription="$item tab";isSelected=tab==item
                setOnClickListener { navigate(item) }
            }
            navigation.addView(nav,LinearLayout.LayoutParams(0,dp(54),1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        val restore=scrollPositions[tab] ?: 0
        scroll.post { if(pageScroll===scroll) scroll.scrollTo(0,restore) }
        root.requestApplyInsets()
    }
    private fun progressCard() {
        val box = column(content,true)
        jobText = label(StudioJobs.status,14f,if(StudioJobs.busy) orange else muted).also { box.addView(it) }
        timingText = label("",12f,muted).also { box.addView(it) }
        if (StudioJobs.busy) {
            gap(box,10)
            progressBar = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progressTintList = android.content.res.ColorStateList.valueOf(orange)
                box.addView(this,LinearLayout.LayoutParams(-1,dp(5)))
            }
            gap(box)
            stopButton = button("Stop after section",enabled=true) { StudioJobs.cancel(); updateProgress() }.also { box.addView(it,LinearLayout.LayoutParams(-1,dp(44))) }
        }
        updateProgress(); gap(content)
    }
    private fun updateProgress() {
        updateFooter()
        val status=StudioJobs.status
        if(jobText?.text?.toString()!=status) jobText?.text=status
        val s=StudioJobs.generation.snapshot()
        val native=StudioJobs.nativeActive
        val summary = if(s.total>0) {
            val speech=if(s.frames>0) " · generated ~${"%.1f".format(s.audioSeconds)}s of speech" else ""
            "Elapsed ${s.elapsed} · current stage ${s.stageElapsed}$speech\n${s.completed} of ${s.total} sections complete"
        } else ""
        timingText?.visibility=if(summary.isEmpty()) View.GONE else View.VISIBLE
        if(timingText?.text?.toString()!=summary) timingText?.text=summary
        progressBar?.max=if(native) 5 else 100
        progressBar?.isIndeterminate=if(native) false else StudioJobs.progress<0
        progressBar?.progress=if(native) (s.stageNumber-1).coerceAtLeast(0) else StudioJobs.progress.coerceAtLeast(0)
        stopButton?.isEnabled = !StudioJobs.stop
        stopButton?.text = if (StudioJobs.isTransfer) "Cancel task" else "Stop generation"
    }
    private fun qualityName(steps: Int)=when(steps) { 4 -> "Fast preview";10 -> "Best quality";else -> "Balanced" }
    private fun qualityDetail(steps: Int)=when(steps) { 4 -> "Quick drafts; words may be less clear";10 -> "Takes longer; suited to final takes";else -> "A balance of speed and quality" }
    private fun chooseQuality(changed: () -> Unit) {
        val values=listOf(4,6,10)
        val names=values.map { "${qualityName(it)} — ${qualityDetail(it)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Generation quality")
            .setSingleChoiceItems(names,values.indexOf(store.prefs.getInt("steps",6))) { dialog,index ->
                store.prefs.edit().putInt("steps",values[index]).apply();dialog.dismiss();changed();updateFooter()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun createScreen() {
        pageTitle("Create speech","Choose a voice and add the words you want to hear.")
        if(!store.ready) {
            val setup=column(content,true);setup.addView(label("Make your phone ready",16f,ink,true));gap(setup,6)
            setup.addView(label("Download the speech model once, then create entirely offline.",13f,muted));gap(setup,10)
            fullButton(setup,"Set up offline speech",true) { navigate("Settings") };gap(content,16)
        }
        StudioJobs.lastProject?.let { id ->
            val dir=File(store.projects,id)
            if(!StudioJobs.busy && File(dir,"full.wav").isFile) {
                val ready=row(content)
                ready.addView(label("Your narration is ready",13f,orange,true),LinearLayout.LayoutParams(0,-2,1f))
                ready.addView(button("Listen") { showProject(dir) },LinearLayout.LayoutParams(dp(90),dp(46)));gap(content,14)
            }
        }
        val selectedVoiceName=store.selectedVoice?.name ?: "Default voice"
        val voice=column(content,true)
        val voiceHeading=row(voice)
        voiceHeading.addView(label("VOICE",10f,muted,true),LinearLayout.LayoutParams(0,-2,1f))
        voiceHeading.addView(button("Manage") { voiceEditor=store.voices.list().isEmpty();navigate("Voices") },LinearLayout.LayoutParams(dp(88),dp(42)))
        gap(voice,6)
        fullButton(voice,"$selectedVoiceName  ▾") { chooseSavedVoice() }
        if(store.voices.list().isEmpty()) { gap(voice,8);voice.addView(label("Clone your voice in the Voices tab, or try the default voice.",12f,muted)) }
        gap(content,18)
        val heading=row(content)
        heading.addView(label("YOUR SCRIPT",10f,muted,true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(label("Draft saves automatically",11f,muted));gap(content,8)
        val script=field(content,"Write or paste your English script…",store.prefs.getString("script","") ?: "",6,"script")
        script.contentDescription="Script";script.filters=arrayOf(InputFilter.LengthFilter(6000))
        val count=label("",11f,muted);gap(content,6);content.addView(count)
        fun updateCount() {
            val words=script.text.toString().trim().split(Regex("\\s+")).count { it.isNotBlank() }
            count.text="${script.length()} / 6,000 characters · $words words"
            if(script.text.isNotBlank()) script.error=null
            updateFooter()
        }
        script.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { updateCount() }
            override fun afterTextChanged(s: Editable?) {}
        });updateCount()
        gap(content,18)
        content.addView(label("QUALITY",10f,muted,true));gap(content,8)
        lateinit var quality: Button
        quality=button("${qualityName(store.prefs.getInt("steps",6))}  ▾") {
            chooseQuality { quality.text="${qualityName(store.prefs.getInt("steps",6))}  ▾" }
        }
        quality.contentDescription="Choose generation quality";content.addView(quality,LinearLayout.LayoutParams(-1,dp(48)))
        gap(content,16)
        content.addView(label("PROJECT NAME · OPTIONAL",10f,muted,true));gap(content,8)
        val title=field(content,"Use the first words of your script",store.prefs.getString("title","") ?: "",key="title")
        title.filters=arrayOf(InputFilter.LengthFilter(80))
        footerReady={ !store.ready || script.text.isNotBlank() }
        footerMessage={ when { !store.ready -> "Finish one-time setup to start creating.";script.text.isBlank() -> "Add your text to get started.";else -> "$selectedVoiceName · ${qualityName(store.prefs.getInt("steps",6))}" } }
        generateSpeechAction={
            if(!store.ready) navigate("Settings") else {
                val text=script.text.toString();ScriptSections.split(text)
                val voiceId=store.prefs.getString("selectedVoiceId",null)?.takeIf { it.isNotEmpty() }
                stopPlayback()
                startJob("Preparing narration…") { StudioJobs.generate(it,text,title.text.toString(),voiceId,store.prefs.getInt("steps",6)) }
            }
        }
    }
    private fun chooseSavedVoice() {
        val voices=store.voices.list()
        val options=(listOf("Default voice · no sample needed")+voices.map { it.name }).toTypedArray()
        val selected=store.prefs.getString("selectedVoiceId",null)
        val current=voices.indexOfFirst { it.id==selected }+1
        AlertDialog.Builder(this).setTitle("Choose voice")
            .setSingleChoiceItems(options,current) { dialog,index ->
                store.prefs.edit().putString("selectedVoiceId",if(index==0) "" else voices[index-1].id).apply()
                dialog.dismiss(); draw()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun editName(title: String,current: String,limit: Int,save: (String) -> Unit) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),0) }
        val name=field(box,"Name",current).apply { filters=arrayOf(InputFilter.LengthFilter(limit));setSelectAllOnFocus(true) }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { save(name.text.toString());dialog.dismiss() }
            catch(e: Exception) { name.error=e.message ?: "Choose a different name.";name.requestFocus() }
        } }
        dialog.show()
    }
    private fun startNewVoice() {
        fun open(clear: Boolean) {
            if(clear) { store.reference.delete();store.prefs.edit().putString("voiceName","").putString("transcript","").apply() }
            voiceEditor=true;voiceNotice="";scrollPositions["Voices"]=0;pageScroll?.scrollTo(0,0);draw()
        }
        val name=store.prefs.getString("voiceName","") ?: ""
        val words=store.prefs.getString("transcript","") ?: ""
        val saved=store.voices.list().any { it.name==name && it.transcript==words && store.reference.isFile && it.reference.readBytes().contentEquals(store.reference.readBytes()) }
        val draft=store.reference.isFile || name.isNotBlank() || words.isNotBlank()
        if(draft && !saved) AlertDialog.Builder(this).setTitle("Continue your voice draft?")
            .setMessage("You have an unfinished voice. Continue it, or start fresh with a new sample.")
            .setPositiveButton("Continue draft") { _,_ -> open(false) }
            .setNegativeButton("Start fresh") { _,_ -> open(true) }.setNeutralButton("Cancel",null).show()
        else open(true)
    }
    private fun voiceMenu(anchor: View,saved: VoiceLibrary.Voice) {
        PopupMenu(this,anchor).apply {
            menu.add("Rename");menu.add("Delete voice")
            setOnMenuItemClickListener { item ->
                when(item.title.toString()) {
                    "Rename" -> editName("Rename voice",saved.name,60) { store.voices.rename(saved.id,it);draw() }
                    else -> AlertDialog.Builder(this@MainActivity).setTitle("Delete ${saved.name}?")
                        .setMessage("This removes the saved voice from this phone. Completed narrations stay in Library.")
                        .setNegativeButton("Keep voice",null).setPositiveButton("Delete") { _,_ -> safely {
                            store.voices.delete(saved.id)
                            if(store.prefs.getString("selectedVoiceId",null)==saved.id) store.prefs.edit().remove("selectedVoiceId").apply()
                            voiceEditor=store.voices.list().isEmpty();voiceNotice="";draw()
                        } }.show()
                };true
            };show()
        }
    }
    private fun voiceScreen() {
        val voices=store.voices.list()
        if(!voiceEditor) {
            val top=row(content)
            top.addView(label("Your voices",27f,ink,true),LinearLayout.LayoutParams(0,-2,1f))
            top.addView(button("+ New voice") { startNewVoice() },LinearLayout.LayoutParams(dp(124),dp(46)))
            gap(content,6);content.addView(label("${voices.size} saved on this phone · choose one to create speech",13f,muted));gap(content,18)
            if(voiceNotice.isNotBlank()) { content.addView(label(voiceNotice,13f,orange));gap(content,14) }
            voices.forEach { saved ->
                val card=column(content,true);val title=row(card)
                title.addView(label(saved.name,18f,ink,true).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
                lateinit var more: Button
                more=button("⋮") { voiceMenu(more,saved) }.apply { contentDescription="Options for ${saved.name}" }
                title.addView(more,LinearLayout.LayoutParams(dp(42),dp(44)))
                val seconds=runCatching { AudioPeaks.durationMs(saved.reference)/1000.0 }.getOrDefault(0.0)
                val selected=store.prefs.getString("selectedVoiceId",null)==saved.id
                card.addView(label("${"%.1f".format(seconds)} sec sample · ${if(selected) "Selected voice" else "Ready to use"}",12f,if(selected) orange else muted));gap(card,12)
                val actions=row(card)
                actions.addView(button("Listen") { play(saved.reference) },LinearLayout.LayoutParams(0,dp(48),1f))
                actions.addView(Space(this),LinearLayout.LayoutParams(dp(8),1))
                actions.addView(button("Use voice",true) { store.prefs.edit().putString("selectedVoiceId",saved.id).apply();navigate("Create") },LinearLayout.LayoutParams(0,dp(48),1f))
                gap(content,14)
            }
            return
        }
        pageTitle("Clone a voice","Add a sample, name it, then review the spoken words.")
        if(voices.isNotEmpty()) { fullButton(content,"‹ Back to saved voices",enabled=!StudioJobs.busy) { hideKeyboard();voiceEditor=false;voiceNotice="";draw() };gap(content,14) }
        val sample=if(store.reference.isFile) runCatching { AudioFiles.read(store.reference,20) }.getOrNull() else null
        val card=column(content,true)
        card.addView(label("1  ·  ADD A SAMPLE",11f,orange,true));gap(card,8)
        card.addView(label(if(sample==null) "Record or import clean speech. Imported audio is converted automatically. Aim for 3–5 seconds." else "${"%.1f".format(sample.seconds)}-second sample ready",13f,muted));gap(card,8)
        val wave=WaveformView(this,orange,muted).apply { audio=sample?.samples }
        card.addView(wave,LinearLayout.LayoutParams(-1,dp(42)))
        recordLabel=label(if(sample==null) "Your sample stays on this phone" else "Listen before continuing",11f,muted).also { card.addView(it) }
        gap(card,10)
        val actions=row(card)
        actions.addView(button(if(recorder?.running==true) "Stop recording" else "Record sample",true) { toggleRecording() },LinearLayout.LayoutParams(0,dp(48),1f))
        actions.addView(Space(this),LinearLayout.LayoutParams(dp(8),1))
        actions.addView(button("Import audio") { chooseFile(41,"audio/*") },LinearLayout.LayoutParams(0,dp(48),1f))
        if(sample!=null) { gap(card,8);fullButton(card,"Listen to reference") { play(store.reference) } }
        gap(content,18)
        content.addView(label("2  ·  NAME YOUR VOICE",11f,orange,true));gap(content,8)
        val name=field(content,"e.g. My narrator",store.prefs.getString("voiceName","") ?: "",key="voiceName")
        name.filters=arrayOf(InputFilter.LengthFilter(60))
        gap(content,18)
        content.addView(label("3  ·  REVIEW THE WORDS",11f,orange,true));gap(content,8)
        fullButton(content,"Extract transcript offline",enabled=!StudioJobs.busy && sample!=null) {
            if(store.prefs.getString("transcript","").isNullOrBlank()) transcribeReference()
            else AlertDialog.Builder(this).setTitle("Extract words again?").setMessage("This replaces your edited transcript with a new offline recognition result.")
                .setNegativeButton("Keep edits",null).setPositiveButton("Extract") { _,_ -> safely { transcribeReference() } }.show()
        }
        gap(content,8)
        val words=field(content,"The exact words spoken in your sample…",store.prefs.getString("transcript","") ?: "",4,"transcript")
        words.filters=arrayOf(InputFilter.LengthFilter(600))
        gap(content,6);content.addView(label("Listen to the sample and correct any missed words. Your draft saves automatically.",12f,muted))
        footerReady={ store.reference.isFile && name.text.isNotBlank() && words.text.isNotBlank() && voices.none { it.name.equals(name.text.toString().trim(),true) } }
        footerMessage={ when {
            !store.reference.isFile -> "Step 1: Record or import a sample."
            name.text.isBlank() -> "Step 2: Give your voice a name."
            voices.any { it.name.equals(name.text.toString().trim(),true) } -> "Choose a name that isn't already saved."
            words.text.isBlank() -> "Step 3: Extract or type the sample's words."
            else -> "Ready to save ${name.text.toString().trim()} on this phone."
        } }
        cloneVoiceAction={
            val title=name.text.toString().trim();val transcript=words.text.toString().trim()
            stopPlayback()
            startJob("Saving your voice…",true) { context ->
                val saved=StudioStore(context).voices.save(title,transcript,StudioStore(context).reference)
                StudioStore(context).prefs.edit().putString("selectedVoiceId",saved.id).apply()
                runOnUiThread { voiceEditor=false;voiceNotice="${saved.name} is ready. Tap Use voice to start creating.";scrollPositions["Voices"]=0;pageScroll?.scrollTo(0,0) }
                StudioJobs.update("${saved.name} saved on this phone")
            }
        }
    }
    private fun narrationMenu(anchor: View,dir: File) {
        val meta=store.metadata(dir)
        PopupMenu(this,anchor).apply {
            menu.add("Rename");menu.add("Export WAV");menu.add("Delete narration")
            setOnMenuItemClickListener { item ->
                when(item.title.toString()) {
                    "Rename" -> editName("Rename narration",meta.getString("title"),80) { store.renameProject(dir,it);draw() }
                    "Export WAV" -> safely { export(dir) }
                    else -> AlertDialog.Builder(this@MainActivity).setTitle("Delete narration?")
                        .setMessage("Remove \"${meta.getString("title")}\" and its audio from this phone? Export a copy first if you want to keep it. Saved voices are unaffected.")
                        .setNegativeButton("Keep narration",null).setPositiveButton("Delete") { _,_ -> safely { store.deleteProject(dir);draw() } }.show()
                };true
            };show()
        }
    }
    private fun libraryScreen() {
        val history=store.history()
        pageTitle("Your library","${history.size} ${if(history.size==1) "narration" else "narrations"} saved on this phone")
        if(history.isEmpty()) {
            val card=column(content,true);card.addView(label("Your first narration starts here",18f,ink,true));gap(card,8)
            card.addView(label("Choose a voice and add your script. Finished audio will be ready to listen to or export here.",13f,muted));gap(card,14)
            fullButton(card,"Create narration",true) { navigate("Create") };return
        }
        val search=field(content,"Search narrations or voices",libraryQuery)
        search.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        gap(content,14)
        val results=column(content)
        fun render() {
            results.removeAllViews()
            val query=libraryQuery.trim()
            val matches=history.mapNotNull { dir -> runCatching { dir to store.metadata(dir) }.getOrNull() }.filter { (_,meta) ->
                query.isEmpty() || (meta.optString("title")+" "+meta.optString("voiceName")+" "+meta.optJSONArray("sections").toString()).contains(query,true)
            }
            if(matches.isEmpty()) {
                results.addView(label("No matching narrations",18f,ink,true));gap(results,8)
                results.addView(label("Try a title, a voice name, or words from your script.",13f,muted));gap(results,12)
                fullButton(results,"Clear search",enabled=true) { search.setText("") };return
            }
            matches.forEach { (dir,meta) ->
                val card=column(results,true);val header=row(card)
                header.addView(label(meta.getString("title"),18f,ink,true).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
                lateinit var more: Button
                more=button("⋮") { narrationMenu(more,dir) }.apply { contentDescription="Options for narration ${meta.getString("title")}" }
                header.addView(more,LinearLayout.LayoutParams(dp(42),dp(44)))
                val duration=runCatching { AudioPeaks.durationMs(File(dir,"full.wav")) }.getOrDefault(0)
                val clock="%d:%02d".format(duration/60000,duration/1000%60)
                val voice=meta.optString("voiceName",if(meta.optBoolean("voice")) "Cloned voice" else "Default voice")
                card.addView(label("$clock · $voice",12f,orange));gap(card,4)
                val date=java.text.SimpleDateFormat("d MMM · HH:mm",java.util.Locale.getDefault()).format(java.util.Date(meta.optLong("created",dir.lastModified())))
                val count=meta.getJSONArray("sections").length()
                card.addView(label("$date · $count ${if(count==1) "section" else "sections"}",11f,muted));gap(card,10)
                card.addView(label(meta.getJSONArray("sections").optString(0),13f,muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END })
                gap(card,12);fullButton(card,"▶ Listen",true) { showProject(dir) };gap(results,14)
            }
        }
        search.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { libraryQuery=s.toString();render() }
            override fun afterTextChanged(s: Editable?) {}
        });render()
    }
    private fun manageSpeechModel() {
        AlertDialog.Builder(this).setTitle("Speech model")
            .setItems(arrayOf("Import model file","Download again")) { _,which ->
                if(which==0) safely { chooseFile(42,"*/*") }
                else AlertDialog.Builder(this).setTitle("Download model again?")
                    .setMessage("This uses about 500 MB of data. Your saved voices and narrations stay on this phone.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Download") { _,_ -> safely { startJob("Connecting to model host…",true) { StudioJobs.download(it) } } }.show()
            }.setNegativeButton("Close",null).show()
    }
    private fun showLicenses() {
        val text=label(assets.open("THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() },12f)
        text.setPadding(dp(20),dp(16),dp(20),dp(16))
        AlertDialog.Builder(this).setTitle("Open-source licenses").setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close",null).show()
    }
    private fun setupScreen() {
        pageTitle("Settings","Offline setup, defaults, and device settings.")
        val card=column(content,true)
        card.addView(label(if(store.ready) "● Ready to create offline" else "Set up offline speech",18f,if(store.ready) orange else ink,true));gap(card,8)
        card.addView(label(if(store.ready) "Voice cloning, transcription and narration run on this phone." else "Download the speech model once. Allow at least 650 MB of free space.",13f,muted));gap(card,14)
        fullButton(card,if(store.ready) "Create narration" else "Download speech model",true) {
            if(store.ready) navigate("Create") else startJob("Connecting to model host…",true) { StudioJobs.download(it) }
        }
        gap(card,8);fullButton(card,if(store.ready) "Manage model files" else "Import model file") {
            if(store.ready) manageSpeechModel() else chooseFile(42,"*/*")
        }
        gap(content,20)
        content.addView(label("DEFAULT QUALITY",10f,muted,true));gap(content,8)
        lateinit var quality: Button
        quality=button("${qualityName(store.prefs.getInt("steps",6))}  ▾") { chooseQuality { quality.text="${qualityName(store.prefs.getInt("steps",6))}  ▾" } }
        content.addView(quality,LinearLayout.LayoutParams(-1,dp(48)))
        gap(content,8);content.addView(label("Fast preview is for drafts. Use Best quality if words sound blurred or incorrect.",12f,muted));gap(content,20)
        val privacy=column(content,true)
        privacy.addView(label("Private by design",16f,ink,true));gap(privacy,8)
        privacy.addView(label("Your samples, transcripts and narrations stay on this phone unless you export them. Internet is used only to download the speech model.",13f,muted));gap(privacy,8)
        privacy.addView(label("Uninstalling removes local voices and projects. Export important narrations first.",12f,muted))
        gap(content,18)
        fullButton(content,if(advancedSettings) "Advanced settings  ▴" else "Advanced settings  ▾") { advancedSettings=!advancedSettings;draw() }
        if(advancedSettings) {
            gap(content,12);val device=column(content,true)
            val info=ActivityManager.MemoryInfo();(getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            device.addView(label("${Build.MANUFACTURER} ${Build.MODEL}",16f,ink,true));gap(device,6)
            device.addView(label("${"%.1f".format(info.totalMem/1073741824.0)} GB RAM · ${if(BuildConfig.MOBILE_FAST) "Accelerated CPU" else "Compatibility CPU"}",12f,muted));gap(device,12)
            lateinit var threads: Button
            threads=button("${store.prefs.getInt("threads",if(BuildConfig.MOBILE_FAST) 2 else 4)} CPU threads  ▾") {
                val values=listOf(2,4)
                AlertDialog.Builder(this).setTitle("CPU threads").setSingleChoiceItems(arrayOf("2 threads","4 threads"),values.indexOf(store.prefs.getInt("threads",if(BuildConfig.MOBILE_FAST) 2 else 4))) { dialog,index ->
                    store.prefs.edit().putInt("threads",values[index]).apply();threads.text="${values[index]} CPU threads  ▾";dialog.dismiss()
                }.setNegativeButton("Cancel",null).show()
            }
            device.addView(threads,LinearLayout.LayoutParams(-1,dp(48)));gap(device,8)
            device.addView(label("More threads can be slower. Two is the accelerated build's default.",12f,muted));gap(device,10)
            val disabled=store.prefs.getString("gpuDisabledBuild",null)==Build.FINGERPRINT+BuildConfig.VERSION_NAME
            device.addView(label(if(disabled) "Processor: CPU · GPU compatibility fallback" else "Processor: Automatic GPU / CPU",12f,muted))
            if(disabled) device.addView(label("GPU execution did not pass on this device. CPU is selected automatically.",12f,muted))
            device.addView(label("VoxCPM 0.5B · Q4 · Native 16 kHz WAV",12f,muted))
        }
        gap(content,14);fullButton(content,"About Vox Studio",enabled=true) {
            AlertDialog.Builder(this).setTitle("Vox Studio ${BuildConfig.VERSION_NAME}")
                .setMessage("Offline English narration and reusable voice profiles.\n\nBuilt with OpenBMB VoxCPM, the VoxCPM.cpp runtime, and Vosk offline speech recognition. This is a development build.")
                .setPositiveButton("Close",null).setNeutralButton("Licenses") { _,_ -> safely { showLicenses() } }.show()
        }
    }
    private fun startJob(label: String, transfer: Boolean = false, task: (Context) -> Unit) {
        require(recorder?.active != true) { "Stop recording before starting another task." }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),52)
        StudioJobs.submit(this,label,transfer,task);observedBusy=true;scrollPositions[tab]=0;pageScroll?.scrollTo(0,0);draw()
    }
    private fun chooseFile(code: Int, mime: String) {
        require(recorder?.active != true) { "Stop recording first." }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type=mime; addCategory(Intent.CATEGORY_OPENABLE) },code)
    }
    private fun showVoiceSamplePicker(input: File, seconds: Double) {
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(10),dp(22),0) }
        form.addView(label("File: %.2f seconds. Choose 3–20 seconds of clean speech. The split button lists consecutive clips; only the chosen clip is saved. A 3–5 second sample is faster on this phone. Enter only the selected clip's words.".format(java.util.Locale.US,seconds)))
        form.addView(label("START TIME (SECONDS)",12f,muted)); gap(form,6)
        val start=field(form,"Start time (seconds)","0")
        gap(form,10); form.addView(label("CLIP LENGTH (SECONDS)",12f,muted)); gap(form,6)
        val duration=field(form,"Clip length (seconds)","%.3f".format(java.util.Locale.US,floor(minOf(5.0,seconds)*1000)/1000))
        for(v in listOf(start,duration)) v.inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        gap(form,10); form.addView(label("EXACT CLIP TRANSCRIPT",12f,muted)); gap(form,6)
        val transcript=field(form,"Exact words spoken in this clip…","",3)
        transcript.filters=arrayOf(InputFilter.LengthFilter(600))
        for(v in listOf(start,duration,transcript)) v.isEnabled=true
        gap(form,10)
        val extract=CheckBox(this).apply {
            text="Extract transcript offline when blank"; setTextColor(ink); isChecked=true
        }
        form.addView(extract)
        val scroll=ScrollView(this).apply { addView(form) }
        val dialog=AlertDialog.Builder(this).setTitle("Choose voice sample").setView(scroll)
            .setNegativeButton("Cancel",null).setNeutralButton("Split into 20s clips",null)
            .setPositiveButton("Use this clip",null).create()
        dialog.setOnDismissListener { input.delete() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val starts=(0 until ceil(seconds/20).toInt()).map { it*20.0 }.filter { seconds-it>=3.0 }
                val names=starts.map { at -> "%.1f–%.1f seconds".format(java.util.Locale.US,at,minOf(at+20,seconds)) }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Select a 20-second clip")
                    .setItems(names) { _,which ->
                        val at=starts[which]
                        start.setText("%.1f".format(java.util.Locale.US,at))
                        duration.setText("%.3f".format(java.util.Locale.US,floor(minOf(20.0,seconds-at)*1000)/1000))
                        transcript.setText("")
                    }.setNegativeButton("Cancel",null).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { safely {
                val at=start.text.toString().toDoubleOrNull() ?: error("Enter a valid start time in seconds.")
                val length=duration.text.toString().toDoubleOrNull() ?: error("Enter a valid clip length in seconds.")
                require(at.isFinite() && length.isFinite() && at>=0 && length in 3.0..20.0 && at+length<=seconds+0.00001) {
                    "Choose 3–20 seconds within the file."
                }
                // Own a separate copy while the dialog's temporary source is cleaned up.
                val chosen=File.createTempFile("voice-chosen-", ".wav",cacheDir)
                input.copyTo(chosen,true)
                val words=transcript.text.toString().trim()
                val autoExtract=extract.isChecked && words.isBlank()
                try { startJob("Saving selected voice clip…",true) { context ->
                    val target=File(context.filesDir,"voice/reference.new.wav")
                    try {
                        target.parentFile!!.mkdirs()
                        AudioImport.convert(chosen,target,{ StudioJobs.checkStop() },startSeconds=at,clipSeconds=length)
                        StudioJobs.checkStop()
                        check(target.renameTo(StudioStore(context).reference)) { "Could not save voice sample." }
                        StudioStore(context).prefs.edit().putString("transcript",words).putBoolean("useVoice",true).apply()
                        if(autoExtract) extractSavedTranscript(context)
                        else StudioJobs.update(if(words.isBlank()) "Voice clip saved · extract its transcript in Voices before cloning" else "Voice clip saved · ready to clone")
                    } finally { chosen.delete(); target.delete() }
                } } catch(e: Exception) { chosen.delete(); throw e }
                dialog.dismiss()
            } }
        }
        dialog.show()
    }
    private fun export(dir: File) {
        require(!StudioJobs.busy) { "Wait for the current task before exporting." }
        exportProject=dir
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="audio/wav"
            putExtra(Intent.EXTRA_TITLE,store.metadata(dir).getString("title").replace(Regex("[^a-zA-Z0-9 _-]"),"").take(60).ifBlank { "Narration" }+".wav")
        },43)
    }
    @Deprecated("Platform activity results are sufficient for this dependency-light prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        when(requestCode) {
            41 -> safely { startJob("Detecting and converting audio…",true) { context ->
                val input=File.createTempFile("audio-source-", ".bin", context.cacheDir)
                try {
                    copyBounded(context,uri,input,100L*1024*1024)
                    val seconds=AudioImport.duration(input)
                    StudioJobs.checkStop()
                    runOnUiThread {
                        if(isDestroyed || isFinishing) input.delete()
                        else showVoiceSamplePicker(input,seconds)
                    }
                    StudioJobs.update("Choose a voice sample from the imported audio")
                } catch(e: Exception) { input.delete();throw e }
            } }
            42 -> safely { startJob("Importing and verifying model…",true) { context ->
                val s=StudioStore(context); s.model.parentFile!!.mkdirs(); val temp=File(s.model.parentFile,"import.part")
                try {
                    require(s.model.parentFile!!.usableSpace>StudioStore.MODEL_BYTES+100_000_000) { "Free at least 650 MB of storage first." }
                    copyBounded(context,uri,temp,StudioStore.MODEL_BYTES)
                    StudioJobs.checkStop(); require(StudioStore.verify(temp)) { "Choose the exact voxcpm-0.5b-q4_k.gguf model from the documented source." }
                    check(temp.renameTo(s.model)); s.prefs.edit().putBoolean("modelVerified",true).apply(); StudioJobs.update("Model installed · ready for offline generation")
                } finally { temp.delete() }
            } }
            43 -> {
                val dir=exportProject ?: return
                io.execute {
                    try {
                        val source=File(dir,"full.wav")
                        contentResolver.openOutputStream(uri,"wt")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                        runOnUiThread { toast("WAV exported") }
                    } catch(e: Exception) { StudioJobs.reportFailure(e.message ?: "Export failed. Please try another location.");runOnUiThread { draw() } }
                }
            }
        }
    }
    private fun copyBounded(context: Context, uri: Uri, output: File, limit: Long) {
        context.contentResolver.openInputStream(uri)!!.buffered().use { input -> output.outputStream().buffered().use { dest ->
            val buffer=ByteArray(256*1024); var total=0L
            while(true) { StudioJobs.checkStop(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=limit) { "Selected file is too large." }; dest.write(buffer,0,n) }
        } }
    }
    private fun showProject(dir: File) {
        require(!StudioJobs.busy && recorder?.active!=true) { "Wait for the current task or recording." }
        stopPlayback()
        val meta=store.metadata(dir);val parts=meta.getJSONArray("sections")
        playerDialog=StudioPlayerDialog(this,meta.getString("title"),
            meta.optString("voiceName",if(meta.getBoolean("voice")) "Cloned voice" else "Model voice"),
            File(dir,"full.wav"),(0 until parts.length()).map { parts.getString(it) to File(dir,"section-$it.wav") },
            exportAudio={ safely { export(dir) } },
            regenerate={ index -> safely { startJob("Preparing section…") { StudioJobs.regenerate(it,dir,index) } } },
            closed={ playerDialog=null }).also { it.show() }
    }
    private fun play(file: File) {
        require(!StudioJobs.busy && recorder?.active != true) { "Wait for the current task or recording." }
        stopPlayback()
        val saved=store.voices.list().find { it.reference==file }
        playerDialog=StudioPlayerDialog(this,saved?.name ?: "Voice sample","Reference audio",file,
            closed={ playerDialog=null }).also { it.show();it.startPreview() }
    }
    private fun stopPlayback() { playerDialog?.dismiss();playerDialog=null }
    private fun transcribeReference() {
        stopPlayback()
        require(store.reference.isFile) { "Record or import a voice sample first." }
        startJob("Extracting transcript offline…",true) { context -> extractSavedTranscript(context) }
    }
    private fun extractSavedTranscript(context: Context) {
        val saved=StudioStore(context)
        try {
            val text=SpeechTranscriber.transcribe(context,saved.reference)
            StudioJobs.checkStop()
            saved.prefs.edit().putString("transcript",text).apply()
            StudioJobs.update("Transcript extracted · listen and correct any missed words before cloning")
        } catch(e: java.util.concurrent.CancellationException) { throw e }
        catch(e: Exception) { StudioJobs.reportFailure("Voice sample kept · ${e.message ?: "Recognition failed. Try again or type its words."}") }
    }
    private fun toggleRecording() {
        if(recorder?.running==true) { recorder?.stop(); return }
        require(!StudioJobs.busy) { "Wait for the current task." }
        require(recorder?.active != true) { "Finishing the previous recording. Please wait." }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),51); return }
        stopPlayback()
        recorder=VoiceRecorder(this) { result -> runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if(!isDestroyed) {
                result.exceptionOrNull()?.let { toast(it.message ?: "Recording failed") }
                draw()
                if(result.isSuccess && hasWindowFocus()) safely { transcribeReference() }
            }
        } }.also { it.start() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); draw()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==51 && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) safely { toggleRecording() }
    }
    private fun safely(action: () -> Unit) { try { action() } catch(e: Exception) { AlertDialog.Builder(this).setTitle("Unable to continue").setMessage(e.message ?: "Please try again.").setPositiveButton("Close",null).show() } }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}

private class WaveformView(context: Context, private val tint: Int, private val muted: Int) : View(context) {
    var audio: ShortArray? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=5f; strokeCap=Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val samples=audio
        if(samples==null) { paint.color=muted; paint.strokeWidth=2f; canvas.drawLine(0f,height/2f,width.toFloat(),height/2f,paint); return }
        paint.color=tint
        val bars=64; val stride=(samples.size/bars).coerceAtLeast(1)
        for(i in 0 until bars) {
            var peak=0
            for(j in i*stride until min((i+1)*stride,samples.size)) peak=max(peak,abs(samples[j].toInt()))
            val h=max(3f,peak/32768f*height*.9f)
            val x=(i+.5f)*width/bars
            canvas.drawLine(x,(height-h)/2,x,(height+h)/2,paint)
        }
    }
}

private class VoiceRecorder(private val context: Context, private val finish: (Result<Unit>) -> Unit) {
    @Volatile var running=false; private set
    @Volatile var active=false; private set
    @Volatile var seconds=0; private set
    fun stop() { running=false }
    fun start() {
        running=true; active=true
        Thread {
            var input: AudioRecord?=null
            val store=StudioStore(context); val temp=File(context.cacheDir,"recorded.wav")
            val target=File(context.filesDir,"voice/recording.new.wav")
            val result=runCatching {
                val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(size>0) { "Microphone does not support 16 kHz recording." }
                require(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
                input=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(size,4096))
                check(input!!.state==AudioRecord.STATE_INITIALIZED) { "Could not open microphone." }
                val all=ShortArray(320000); val buffer=ShortArray(1024); var count=0
                input!!.startRecording()
                while(running && count<all.size) {
                    val n=input!!.read(buffer,0,min(buffer.size,all.size-count))
                    check(n>0) { "Microphone stopped unexpectedly." }
                    buffer.copyInto(all,count,0,n); count+=n; seconds=count/16000
                }
                input!!.stop()
                require(count>=48000) { "Record at least 3 seconds. Previous reference kept." }
                AudioFiles.write(temp,all.copyOf(count)); target.parentFile!!.mkdirs(); AudioFiles.reference(temp,target)
                check(target.renameTo(store.reference))
                store.prefs.edit().putString("transcript","").putBoolean("useVoice",true).apply()
            }
            input?.release(); running=false; active=false; temp.delete(); target.delete(); finish(result)
        }.apply { name="VoiceRecorder"; start() }
    }
}
