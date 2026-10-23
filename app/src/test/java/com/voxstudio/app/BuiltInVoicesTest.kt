package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltInVoicesTest {
    private val catalog=BuiltInVoices.parse(File("src/main/assets/voices/catalog.json").readText())
    @Test fun catalogHasTwentyDistinctBalancedVoices() {
        assertEquals(20,catalog.size)
        assertEquals(20,catalog.map { it.id }.toSet().size)
        assertEquals(20,catalog.map { it.name.lowercase() }.toSet().size)
        assertEquals(10,catalog.count { it.gender=="female" })
        assertEquals(10,catalog.count { it.gender=="male" })
    }
    @Test fun everyVoiceHasAUsableTranscriptAndSafeFileName() {
        catalog.forEach {
            assertTrue(it.id,it.transcript.split(" ").size>=8)
            assertTrue(it.id,it.file.matches(Regex("[a-z0-9-]+\\.wav")))
            assertFalse(it.id,it.voiceId.isBlank() || !it.voiceId.startsWith(BuiltInVoices.PREFIX))
        }
    }
    @Test fun labelsReadNaturally() {
        val aria=catalog.first { it.id=="aria" }
        assertEquals("Female · American · Warm narrator",aria.label)
    }
}
