package com.voxstudio.app

/**
 * Bahasa Indonesia text handling for narration.
 *
 * VoxCPM-0.5B officially supports only Chinese/English, VoxCPM2 adds Indonesian
 * (30 languages). Regardless of model, Indonesian scripts need:
 * - sentence splitting that does not break on local abbreviations (Sdr., Jl., No., …),
 *   thousand/decimal separators (1.000, 3,5), times (07.30), or ellipsis,
 * - number/abbreviation normalization so the model hears words, not digits.
 */
object IndonesianText {
    private const val DOT = ""

    private val ABBR = listOf(
        "Sdr", "Sdri", "Bpk", "Ibu", "Jl", "No", "Kec", "Kab", "Kota", "Prov",
        "Ds", "Rt", "Rw", "Rp", "Yth", "dll", "dsb", "dst", "sda", "an", "ub", "da",
        "S\\.H", "M\\.Pd", "S\\.E", "S\\.T", "S\\.Si", "M\\.Si", "Dr", "Drs", "Ir", "Hj", "H"
    )

    private val UNITS = arrayOf("", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan")

    fun numberToWords(n: Long): String {
        if (n == 0L) return "nol"
        if (n < 0) return "minus " + numberToWords(-n)
        return convert(n).trim()
    }

    private fun convert(n: Long): String = when {
        n < 10 -> UNITS[n.toInt()]
        n == 10L -> "sepuluh"
        n == 11L -> "sebelas"
        n < 20 -> UNITS[(n - 10).toInt()] + " belas"
        n < 100 -> {
            val t = n / 10; val r = n % 10
            (if (t == 1L) "sepuluh" else UNITS[t.toInt()] + " puluh") + if (r == 0L) "" else " " + convert(r)
        }
        n < 200 -> "seratus" + if (n % 100 == 0L) "" else " " + convert(n % 100)
        n < 1000 -> UNITS[(n / 100).toInt()] + " ratus" + if (n % 100 == 0L) "" else " " + convert(n % 100)
        n < 2000 -> "seribu" + if (n % 1000 == 0L) "" else " " + convert(n % 1000)
        n < 1_000_000 -> convert(n / 1000) + " ribu" + if (n % 1000 == 0L) "" else " " + convert(n % 1000)
        n < 1_000_000_000 -> convert(n / 1_000_000) + " juta" + if (n % 1_000_000 == 0L) "" else " " + convert(n % 1_000_000)
        n < 1_000_000_000_000 -> convert(n / 1_000_000_000) + " miliar" + if (n % 1_000_000_000 == 0L) "" else " " + convert(n % 1_000_000_000)
        else -> convert(n / 1_000_000_000_000) + " triliun" + if (n % 1_000_000_000_000 == 0L) "" else " " + convert(n % 1_000_000_000_000)
    }

    private fun digitsToWords(digits: String): String =
        digits.map { UNITS[it - '0'] }.joinToString(" ")

    /** "21" -> "dua puluh satu"; used for decimals, times, and fallbacks. */
    fun digitGroupToWords(raw: String): String {
        val clean = raw.replace(".", "").replace(",", ".")
        val num = clean.toLongOrNull()
        if (num != null && num in 0..999_999_999_999_999) return numberToWords(num)
        return digitsToWords(raw.filter { it.isDigit() })
    }

    /**
     * Expand numbers/symbols in running text so TTS hears words.
     * Idempotent for text that already spells numbers out.
     */
    fun normalize(text: String): String {
        var s = text
        // Currency: Rp5.000 / Rp 5.000.000 -> "lima ribu rupiah".
        s = Regex("Rp\\s?(\\d[\\d.]*(?:,\\d+)?)").replace(s) {
            digitGroupToWords(it.groupValues[1]) + " rupiah"
        }
        // Percent: 75% / 3,5% -> "tujuh puluh lima persen".
        s = Regex("(\\d[\\d.]*(?:,\\d+)?)\\s?%").replace(s) {
            decimalToWords(it.groupValues[1]) + " persen"
        }
        // Degrees: 35°C -> "tiga puluh lima derajat celcius".
        s = Regex("(\\d[\\d.]*(?:,\\d+)?)\\s?°\\s?C").replace(s) {
            decimalToWords(it.groupValues[1]) + " derajat celcius"
        }
        // Times HH.MM / HH:MM -> "pukul tujuh tiga puluh" (00 -> "tepat").
        s = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b").replace(s) {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
            if (m == 0) "pukul " + numberToWords(h.toLong()) + " tepat"
            else "pukul " + numberToWords(h.toLong()) + " " + numberToWords(m.toLong())
        }
        // Ordinals: ke-1 -> "pertama", ke-3 -> "ketiga".
        s = Regex("\\bke-(\\d+)\\b").replace(s) {
            val n = it.groupValues[1].toLongOrNull() ?: return@replace it.value
            if (n == 1L) "pertama" else "ke" + numberToWords(n)
        }
        // Decimals with comma: 3,5 -> "tiga koma lima".
        s = Regex("\\b(\\d[\\d.]*) ,(\\d+)".replace(" ", "")).replace(s) {
            decimalToWords(it.groupValues[1] + "," + it.groupValues[2])
        }
        // Thousand-separated integers: 1.000.000 -> words.
        s = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").replace(s) { digitGroupToWords(it.value) }
        // Remaining standalone integers (years, counts): 2026 -> "dua ribu dua puluh enam".
        s = Regex("\\b\\d+\\b").replace(s) {
            it.value.toLongOrNull()?.let { n -> numberToWords(n) } ?: it.value
        }
        // Common abbreviations spoken aloud.
        val words = mapOf(
            "Sdr." to "Saudara", "Sdri." to "Saudari", "Bpk." to "Bapak",
            "Jl." to "Jalan", "No." to "Nomor", "Kec." to "Kecamatan",
            "Kab." to "Kabupaten", "Prov." to "Provinsi",
            "Yth." to "Yang terhormat", "dll." to "dan lain lain",
            "dsb." to "dan sebagainya", "dst." to "dan seterusnya"
        )
        for ((k, v) in words) s = s.replace(k, v)
        return s.replace(Regex("[\\t ]+"), " ").trim()
    }

    private fun decimalToWords(raw: String): String {
        val noThousand = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").replace(raw) { it.value.replace(".", "") }
        val parts = noThousand.split(",")
        if (parts.size == 2) {
            val head = parts[0].toLongOrNull()?.let { numberToWords(it) } ?: digitsToWords(parts[0].filter { c -> c.isDigit() })
            val tail = digitsToWords(parts[1].filter { c -> c.isDigit() })
            return "$head koma $tail".trim()
        }
        return digitGroupToWords(raw)
    }

    /**
     * Split running text into sentences without breaking on Indonesian abbreviations,
     * thousand separators, times, ellipsis, or URLs. Newlines always break.
     */
    fun splitSentences(text: String): List<String> {
        val clean = text.trim().replace(Regex("[\\t ]+"), " ")
        if (clean.isBlank()) return emptyList()
        var s = clean
        // Protect ellipsis and URLs/emails first.
        s = s.replace("...", "…")
        // Protect thousand separators (1.000) and times (07.30) dots.
        s = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").replace(s) { it.value.replace(".", DOT) }
        s = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b").replace(s) { it.value.replace(".", DOT).replace(":", DOT) }
        // Protect known abbreviations: "Jl." -> "Jl".
        for (a in ABBR) {
            val base = a.replace("\\", "")
            s = Regex("(?<=\\b|\\s)" + Regex.escape(base) + "\\.").replace(s) { base + DOT }
        }
        // Protect single-letter initials: "S. H." / "A. Yani".
        s = Regex("\\b([A-Z])\\.").replace(s) { it.groupValues[1] + DOT }
        val parts = s.split(Regex("\\n+|(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        return parts.map { it.replace(DOT, ".") }
    }
}
