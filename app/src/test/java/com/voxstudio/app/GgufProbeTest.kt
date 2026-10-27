package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class GgufProbeTest {
    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun gstr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return le64(b.size.toLong()) + b
    }
    private fun kvString(key: String, value: String) = gstr(key) + le32(8) + gstr(value)
    private fun kvU32(key: String, value: Int) = gstr(key) + le32(4) + le32(value)
    private fun tensor(name: String) = gstr(name) + le32(1) + le64(4) + le32(0) + le64(0)

    private fun writeGguf(dir: File, name: String, arch: String?, keys: List<ByteArray>, tensors: List<String>): File {
        val f = File(dir, name)
        RandomAccessFile(f, "rw").use { out ->
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
            out.write(le32(3))
            out.write(le64(tensors.size.toLong()))
            val allKeys = (if (arch != null) listOf(kvString("general.architecture", arch)) else emptyList()) + keys
            out.write(le64(allKeys.size.toLong()))
            allKeys.forEach { out.write(it) }
            tensors.forEach { out.write(tensor(it)) }
        }
        return f
    }

    @Test fun fullModelPasses() {
        val dir = Files.createTempDirectory("gguf-full").toFile()
        try {
            val f = writeGguf(dir, "full.gguf", "VoxCPM2",
                listOf(kvString("tokenizer.ggml.model", "voxcpm"), kvU32("voxcpm_patch_size", 4)),
                listOf("base_lm.blk.0.attn_q.weight", "audio_vae.encoder.0.weight", "locdit.proj.weight"))
            val r = GgufProbe.probe(f)
            assertNull(r.error)
            assertTrue(r.hasTokenizer); assertTrue(r.hasAudioVae); assertTrue(r.hasLm || r.hasDit)
            assertTrue(r.isFull)
        } finally { dir.deleteRecursively() }
    }

    @Test fun baseLmOnlyIsPartial() {
        val dir = Files.createTempDirectory("gguf-partial").toFile()
        try {
            val f = writeGguf(dir, "baselm.gguf", "VoxCPM2", emptyList(),
                listOf("base_lm.blk.0.attn_q.weight", "base_lm.blk.1.attn_q.weight"))
            val r = GgufProbe.probe(f)
            assertNull(r.error)
            assertFalse(r.isFull)
            assertTrue(r.missing.any { it.contains("tokenizer") })
            assertTrue(r.missing.any { it.contains("AudioVAE") })
            assertTrue(GgufProbe.diagnose(f).contains("BaseLM"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun nonGgufIsRejected() {
        val dir = Files.createTempDirectory("gguf-bad").toFile()
        try {
            val f = File(dir, "bad.gguf").also { it.writeText("not a gguf file at all........") }
            val r = GgufProbe.probe(f)
            assertNotNull(r.error)
            assertFalse(r.isFull)
        } finally { dir.deleteRecursively() }
    }
}
