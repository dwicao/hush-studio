package com.voxstudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ChunkProjects {
    fun create(context: Context,text: String,title: String,voiceId: String?,steps: Int): File {
        val store=StudioStore(context)
        require(text.isNotBlank() && text.length<=6000) { "Add a script up to 6,000 characters." }
        val chunks=text.trim().split(Regex("\\n\\s*\\n")).flatMap { paragraph ->
            val pieces=ScriptSections.split(paragraph)
            val grouped=mutableListOf<String>()
            for(piece in pieces) {
                if(grouped.isNotEmpty() && grouped.last().length+piece.length+1<=220) grouped[grouped.lastIndex]+=" "+piece
                else grouped.add(piece)
            };grouped
        }
        val voice=if(voiceId.isNullOrBlank()) null else store.voices.find(voiceId) ?: error("Choose an available voice.")
        val dir=File(store.projects,UUID.randomUUID().toString()).also { check(it.mkdirs()) }
        try {
            voice?.reference?.copyTo(File(dir,"reference.wav"),true)
            store.saveMetadata(dir,JSONObject().put("studio",true).put("title",title.ifBlank { chunks.first().split(" ").take(7).joinToString(" ") }.take(80))
                .put("created",System.currentTimeMillis()).put("sections",JSONArray(chunks)).put("voice",voice!=null)
                .put("voiceId",voice?.id ?: "").put("voiceName",voice?.name ?: "Default voice")
                .put("transcript",voice?.transcript ?: "").put("steps",steps))
            store.prefs.edit().putString("studioProjectId",dir.name).apply()
            return dir
        } catch(e: Exception) { dir.deleteRecursively();throw e }
    }
    fun edit(context: Context,dir: File,index: Int,text: String) {
        require(!StudioJobs.busy) { "Wait for generation to finish before editing." }
        val words=text.trim();require(words.isNotBlank() && words.length<=220) { "Keep this chunk between 1 and 220 characters." }
        val store=StudioStore(context);val meta=store.metadata(dir);val parts=meta.getJSONArray("sections")
        require(index in 0 until parts.length())
        if(parts.getString(index)==words)return
        // Preserve previous takes while invalidating audio that no longer matches the text.
        for(name in listOf("section-$index.wav","full.wav")) {
            val file=File(dir,name)
            if(file.exists())check(file.renameTo(File(dir,name.removeSuffix(".wav")+".previous-${System.currentTimeMillis()}.wav")))
        }
        parts.put(index,words);store.saveMetadata(dir,meta)
    }
}
