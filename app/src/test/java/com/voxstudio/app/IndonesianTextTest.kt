package com.voxstudio.app

import org.junit.Assert.*
import org.junit.Test

class IndonesianTextTest {
    @Test fun numbersSpelledInIndonesian() {
        assertEquals("nol", IndonesianText.numberToWords(0))
        assertEquals("dua belas", IndonesianText.numberToWords(12))
        assertEquals("dua puluh satu", IndonesianText.numberToWords(21))
        assertEquals("seratus", IndonesianText.numberToWords(100))
        assertEquals("seribu", IndonesianText.numberToWords(1000))
        assertEquals("dua ribu dua puluh enam", IndonesianText.numberToWords(2026))
        assertEquals("lima juta", IndonesianText.numberToWords(5_000_000))
    }
    @Test fun normalizeExpandsCurrencyPercentAndTime() {
        assertTrue(IndonesianText.normalize("Rp5.000").contains("rupiah"))
        assertTrue(IndonesianText.normalize("75%").contains("persen"))
        assertTrue(IndonesianText.normalize("ke-3").contains("ketiga"))
        assertTrue(IndonesianText.normalize("07.30").contains("pukul"))
        assertTrue(IndonesianText.normalize("Jl. Merdeka No. 10").contains("Jalan"))
    }
    @Test fun splitterKeepsAbbreviationsAndDecimals() {
        val parts = IndonesianText.splitSentences("Saya tinggal di Jl. Merdeka No. 10. Harga Rp5.000, murah!")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("Jl. Merdeka No. 10"))
        val time = IndonesianText.splitSentences("Berangkat 07.30 pagi. Sampai jam 8.")
        assertEquals(2, time.size)
    }
    @Test fun scriptSectionsStillBoundsEnglishFixtures() {
        val text = "Hello there. " + "a".repeat(500) + "\nThe next section!"
        val sections = ScriptSections.split(text)
        assertTrue(sections.all { it.length <= 220 })
        assertEquals(text.filterNot { it.isWhitespace() }, sections.joinToString("").filterNot { it.isWhitespace() })
    }
    @Test fun scriptSectionsHandlesIndonesianParagraphs() {
        val chunks = ChunkProjects.split("Pertama. Kedua.\n\nKetiga di Jl. Merdeka.")
        assertEquals(2, chunks.size)
        assertTrue(chunks[1].contains("Jl. Merdeka"))
    }
}
