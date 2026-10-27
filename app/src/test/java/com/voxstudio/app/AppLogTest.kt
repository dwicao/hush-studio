package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppLogTest {
    private fun freshDir(): File {
        AppLog.resetForTesting()
        return Files.createTempDirectory("applog").toFile()
    }

    @Test fun buffersAndTrimsToRingLimit() {
        val dir = freshDir()
        try {
            AppLog.init(dir, "test")
            repeat(450) { AppLog.i("t", "line $it") }
            val tail = AppLog.tail(500)
            assertEquals(400, tail.lines().size)
            assertTrue(tail.contains("line 449"))
            assertFalse(tail.contains("line 0"))
        } finally { AppLog.resetForTesting(); dir.deleteRecursively() }
    }

    @Test fun scrubsAppPathsAndKeepsSessionsBounded() {
        val dir = freshDir()
        try {
            AppLog.init(dir, "test")
            AppLog.i("p", "model at ${dir.absolutePath}/model/x.gguf")
            assertTrue(AppLog.tail(5).contains("<app>/model/x.gguf"))
            // Simulate old sessions; init keeps only KEEP_FILES newest.
            AppLog.resetForTesting()
            val logs = File(dir, "logs").also { it.mkdirs() }
            (1..5).forEach { File(logs, "app-2026010$it-000000.log").writeText("old") }
            AppLog.init(dir, "test2")
            val kept = logs.listFiles { f -> f.extension == "log" }!!
            assertTrue("kept=${kept.map { it.name }}", kept.size <= 3)
        } finally { AppLog.resetForTesting(); dir.deleteRecursively() }
    }

    @Test fun exportFallsBackToMemoryWithoutInit() {
        AppLog.resetForTesting()
        AppLog.w("t", "hello")
        assertTrue(AppLog.exportText().contains("hello"))
        AppLog.resetForTesting()
    }
}
