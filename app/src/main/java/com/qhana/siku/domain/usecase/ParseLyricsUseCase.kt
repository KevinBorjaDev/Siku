package com.qhana.siku.domain.usecase

import com.qhana.siku.ui.viewmodel.LyricLine
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ParseLyricsUseCase @Inject constructor() {

    operator fun invoke(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()

        val lines = mutableListOf<LyricLine>()
        // Regex para capturar [mm:ss.xx] o [mm:ss]
        val regex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?](.*)""")

        var hasTimestamps = false

        text.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                hasTimestamps = true
                val (min, sec, msStr, content) = match.destructured
                val ms = if (msStr.isNotEmpty()) {
                    if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                } else 0L

                val timeMillis = TimeUnit.MINUTES.toMillis(min.toLong()) +
                        TimeUnit.SECONDS.toMillis(sec.toLong()) +
                        ms

                val cleanContent = content.trim()
                // Filtrar líneas vacías o que sean solo un punto/guión (artefactos comunes)
                if (cleanContent.isNotEmpty() && cleanContent != "." && cleanContent != "-") {
                    lines.add(LyricLine(timeMillis, cleanContent))
                }
            } else if (line.isNotBlank() && !line.startsWith("[")) {
                if (!hasTimestamps) {
                    lines.add(LyricLine(0, line.trim()))
                }
            }
        }

        return if (hasTimestamps) {
            lines.sortedBy { it.startTime }
        } else {
            text.lines()
                .filter { it.isNotBlank() }
                .map { LyricLine(0, it) }
        }
    }
}

