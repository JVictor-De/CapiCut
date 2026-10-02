package com.capicut.audiointel.transcript

import com.capicut.audiointel.whisper.Transcript
import com.capicut.audiointel.whisper.TranscriptWord
import com.tharunbirla.librecuts.models.SubtitleCue
import java.util.Locale

data class FillerHit(
    val word: TranscriptWord,
    val selected: Boolean = true
)

object FillerWordDetector {
    val defaultLexiconPt: Set<String> = setOf(
        "né", "né?", "então", "tipo", "assim", "aí", "ah", "eh", "uhm", "hum",
        "tipo assim", "então né", "tá", "tá?", "ok", "okay", "é", "hã"
    )

    fun findFillers(transcript: Transcript, extra: Set<String> = emptySet()): List<TranscriptWord> {
        val lexicon = defaultLexiconPt + extra.map { it.lowercase(Locale.getDefault()) }
        return transcript.words.filter { word ->
            val n = word.text.lowercase(Locale.getDefault()).trim().trimEnd('.', ',', '?', '!')
            n in lexicon
        }
    }

    fun toHits(transcript: Transcript, extra: Set<String> = emptySet()): List<FillerHit> =
        findFillers(transcript, extra).map { FillerHit(it, selected = true) }

    /**
     * Keep windows after removing selected filler spans from [0, durationMs].
     */
    fun keepSegmentsAfterRemoval(
        durationMs: Long,
        hits: List<FillerHit>
    ): List<Pair<Long, Long>> {
        val cuts = hits.filter { it.selected }
            .sortedBy { it.word.startMs }
            .map { it.word.startMs to it.word.endMs }
        if (cuts.isEmpty()) return listOf(0L to durationMs.coerceAtLeast(0L))
        val keep = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for ((start, end) in cuts) {
            val s = start.coerceAtLeast(cursor)
            if (s > cursor) keep.add(cursor to s)
            cursor = maxOf(cursor, end)
        }
        if (cursor < durationMs) keep.add(cursor to durationMs)
        return keep.filter { it.second > it.first }
    }
}

object TranscriptExporter {
    fun toSrt(cues: List<SubtitleCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            sb.append(i + 1).append('\n')
            sb.append(formatTs(cue.startTimeMs)).append(" --> ").append(formatTs(cue.endTimeMs)).append('\n')
            sb.append(cue.text).append("\n\n")
        }
        return sb.toString()
    }

    fun wordsToCues(words: List<TranscriptWord>, maxGapMs: Long = 800): List<SubtitleCue> {
        if (words.isEmpty()) return emptyList()
        val cues = mutableListOf<SubtitleCue>()
        var start = words.first().startMs
        var end = words.first().endMs
        val buf = StringBuilder(words.first().text)
        for (i in 1 until words.size) {
            val w = words[i]
            if (w.startMs - end > maxGapMs) {
                cues.add(SubtitleCue(start, end, buf.toString().trim()))
                start = w.startMs
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(w.text)
            end = w.endMs
        }
        cues.add(SubtitleCue(start, end, buf.toString().trim()))
        return cues
    }

    private fun formatTs(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        val milli = total % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
