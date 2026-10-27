package com.voxstudio.app

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads only a GGUF file's header (magic, metadata keys, tensor names) to tell
 * a full VoxCPM model apart from a partial/component file — e.g. a BaseLM-only
 * GGUF, which the native runtime cannot initialize on its own.
 *
 * Never reads tensor data, so probing a multi-GB file costs kilobytes.
 */
object GgufProbe {
    data class Report(
        val magicOk: Boolean,
        val architecture: String,
        val metadataKeys: Int,
        val tensorCount: Long,
        val tensorsScanned: Int,
        val hasTokenizer: Boolean,
        val hasVoxConfig: Boolean,
        val hasAudioVae: Boolean,
        val hasDit: Boolean,
        val hasLm: Boolean,
        val error: String?
    ) {
        val isFull: Boolean get() = error == null && magicOk &&
            (architecture.equals("voxcpm", true) || architecture.equals("voxcpm2", true) || hasVoxConfig) &&
            hasTokenizer && hasAudioVae && (hasDit || hasLm)
        val missing: List<String> get() {
            val out = mutableListOf<String>()
            if (architecture.isBlank() && !hasVoxConfig) out += "arsitektur VoxCPM"
            if (!hasTokenizer) out += "tokenizer"
            if (!hasAudioVae) out += "AudioVAE"
            if (!hasDit && !hasLm) out += "model bahasa/difusi"
            return out
        }
    }

    private const val MAX_TENSORS_SCANNED = 6000

    fun probe(file: File): Report {
        try {
            RandomAccessFile(file, "r").use { f ->
                if (f.length() < 24) return invalid("terlalu kecil untuk file GGUF")
                val magic = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
                if (magic != "GGUF") return invalid("bukan file GGUF (magic=$magic)")
                val version = u32(f)
                if (version != 3u) return invalid("versi GGUF $version tidak dikenal")
                val nTensors = u64(f)
                val nKv = u64(f)
                if (nKv > 100_000 || nTensors > 100_000) return invalid("header tidak wajar")
                var arch = ""
                var keys = 0
                var hasTok = false
                var hasVox = false
                repeat(nKv.toInt()) {
                    val key = str(f) ?: return invalid("metadata rusak")
                    keys++
                    if (key == "general.architecture") {
                        val t = u32(f)
                        arch = if (t == 8u) str(f) ?: "" else { skipValue(f, t); "" }
                    } else {
                        val t = u32(f)
                        if (key.startsWith("tokenizer.")) hasTok = true
                        if (key.startsWith("voxcpm")) hasVox = true
                        skipValue(f, t)
                    }
                }
                var hasVae = false; var hasDit = false; var hasLm = false
                var scanned = 0
                val total = nTensors.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                while (scanned < total && scanned < MAX_TENSORS_SCANNED && !(hasVae && hasDit && hasLm)) {
                    val name = str(f)?.lowercase() ?: break
                    // GGUF tensor info: name, n_dims:u32, dims[n_dims]:u64, type:u32, offset:u64
                    val nDims = u32(f).toInt().coerceIn(0, 8)
                    repeat(nDims) { u64(f) }
                    u32(f); u64(f) // type, offset
                    scanned++
                    if ("audio_vae" in name || "audiovae" in name || name.startsWith("vae")) hasVae = true
                    if ("dit" in name || "locdit" in name || "cfm" in name || "unified" in name) hasDit = true
                    if (".blk." in name || "token_embd" in name || "output_norm" in name ||
                        "base_lm" in name || "residual_lm" in name || "locenc" in name) hasLm = true
                }
                return Report(true, arch, keys, nTensors, scanned, hasTok, hasVox, hasVae, hasDit, hasLm, null)
            }
        } catch (e: Exception) {
            return invalid(e.message ?: "tidak bisa dibaca")
        }
    }

    private fun invalid(reason: String) = Report(false, "", 0, 0, 0, false, false, false, false, false, reason)

    private fun u32(f: RandomAccessFile): UInt {
        val b = ByteArray(4).also { f.readFully(it) }
        return (b[0].toUInt() and 255u) or ((b[1].toUInt() and 255u) shl 8) or
            ((b[2].toUInt() and 255u) shl 16) or ((b[3].toUInt() and 255u) shl 24)
    }

    private fun u64(f: RandomAccessFile): Long {
        val b = ByteArray(8).also { f.readFully(it) }
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 255)
        return v
    }

    private fun str(f: RandomAccessFile): String? = runCatching {
        val n = u64(f).coerceAtMost(8 * 1024 * 1024)
        ByteArray(n.toInt()).also { f.readFully(it) }.toString(Charsets.UTF_8)
    }.getOrNull()

    private fun skipValue(f: RandomAccessFile, type: UInt) {
        when (type) {
            0u, 1u, 7u -> f.skipBytes(1) // u8, i8, bool
            2u, 3u -> f.skipBytes(2) // u16, i16
            4u, 5u, 6u -> f.skipBytes(4) // u32, i32, f32
            10u, 11u, 12u -> f.skipBytes(8) // u64, i64, f64
            8u -> { val n = u64(f).coerceAtMost(64 * 1024 * 1024); f.skipBytes(n.toInt().coerceAtLeast(0)) }
            9u -> {
                val elem = u32(f); val n = u64(f).coerceAtMost(1_000_000)
                repeat(n.toInt()) { skipValue(f, elem) }
            }
            else -> throw IllegalArgumentException("tipe metadata GGUF $type tidak dikenal")
        }
    }

    /** Short Indonesian diagnosis line for dialogs and error messages. */
    fun diagnose(file: File): String {
        val r = probe(file)
        if (r.error != null) return "File ini ${r.error}."
        if (r.isFull) return "Model VoxCPM penuh (${r.architecture.ifBlank { "tanpa label arsitektur" }}, ${r.tensorCount} tensor)."
        val missing = r.missing.joinToString(", ")
        val archNote = if (r.architecture.isNotBlank()) "arsitektur ${r.architecture}, " else ""
        return "File ini ${archNote}tidak lengkap untuk dipakai langsung (kurang: $missing). " +
            "Contohnya file BaseLM saja tidak bisa dipakai tanpa komponen lainnya. " +
            "Pakai satu file GGUF VoxCPM penuh hasil ekspor resmi."
    }
}
