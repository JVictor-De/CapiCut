package com.capicut.audiointel

import android.net.Uri
import com.capicut.audiointel.dsp.AudioChainConfig
import com.capicut.audiointel.dsp.AudioChainStep
import com.capicut.audiointel.project.LocalProjectMeta
import com.capicut.audiointel.project.ProjectIndex
import com.capicut.audiointel.transcript.FillerHit
import com.capicut.audiointel.transcript.FillerWordDetector
import com.capicut.audiointel.transcript.TranscriptExporter
import com.capicut.audiointel.vad.AutoCutApplier
import com.capicut.audiointel.vad.SileroVadEngine
import com.capicut.audiointel.vad.SpeechSegment
import com.capicut.audiointel.whisper.Transcript
import com.capicut.audiointel.whisper.TranscriptWord
import com.google.gson.Gson
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CapicutUnitTest {

    @Test
    fun audioChainDefaultsExcludeOnnxSteps() {
        val cfg = AudioChainConfig()
        assertTrue(cfg.isOn(AudioChainStep.HIGHPASS))
        assertTrue(cfg.isOn(AudioChainStep.COMPRESSOR))
        assertTrue(cfg.isOn(AudioChainStep.DEESSER))
        assertTrue(cfg.isOn(AudioChainStep.LOUDNORM))
        assertFalse(cfg.isOn(AudioChainStep.DENOISE))
        assertFalse(cfg.isOn(AudioChainStep.DEREVERB))
    }

    @Test
    fun srtExportUsesCommaMillisAndLocaleSafeDigits() {
        val srt = TranscriptExporter.toSrt(
            listOf(SubtitleCue(3661, 5000, "olá"))
        )
        assertTrue(srt.contains("00:00:03,661 --> 00:00:05,000"))
        assertTrue(srt.contains("olá"))
    }

    @Test
    fun wordsToCuesSplitsOnGap() {
        val words = listOf(
            TranscriptWord(0, 200, "oi"),
            TranscriptWord(250, 400, "tudo"),
            TranscriptWord(2000, 2300, "bem")
        )
        val cues = TranscriptExporter.wordsToCues(words, maxGapMs = 800)
        assertEquals(2, cues.size)
        assertEquals("oi tudo", cues[0].text)
        assertEquals("bem", cues[1].text)
    }

    @Test
    fun fillerDetectorFindsPortugueseFillers() {
        val t = Transcript(
            words = listOf(
                TranscriptWord(0, 100, "tipo"),
                TranscriptWord(120, 200, "vídeo")
            )
        )
        val fillers = FillerWordDetector.findFillers(t)
        assertEquals(1, fillers.size)
        assertEquals("tipo", fillers[0].text)
    }

    @Test
    fun fillerKeepSegmentsRemovesSelectedHits() {
        val hits = listOf(
            FillerHit(TranscriptWord(1000, 1200, "hum"), selected = true),
            FillerHit(TranscriptWord(3000, 3200, "né"), selected = false)
        )
        val keep = FillerWordDetector.keepSegmentsAfterRemoval(5000, hits)
        assertEquals(listOf(0L to 1000L, 1200L to 5000L), keep)
    }

    @Test
    fun autoCutApplierSingleSegmentIsTrim() {
        val uri = mock(Uri::class.java)
        val ops = AutoCutApplier.toOperations(uri, listOf(SpeechSegment(100, 900)))
        assertEquals(1, ops.size)
        val trim = ops[0] as EditOperation.Trim
        assertEquals(100L, trim.startMs)
        assertEquals(900L, trim.endMs)
    }

    @Test
    fun autoCutApplierMultipleSegmentsBecomeTrimPlusMerge() {
        val uri = mock(Uri::class.java)
        val ops = AutoCutApplier.toOperations(
            uri,
            listOf(SpeechSegment(0, 500), SpeechSegment(2000, 2500), SpeechSegment(4000, 4500))
        )
        assertEquals(2, ops.size)
        assertTrue(ops[0] is EditOperation.Trim)
        val merge = ops[1] as EditOperation.Merge
        assertEquals(2, merge.items.size)
        assertEquals(2000L, merge.items[0].trimStartMs)
        assertEquals(2500L, merge.items[0].trimEndMs)
        assertEquals(uri, merge.items[0].uri)
    }

    @Test
    fun autoCutApplierEmptyReturnsEmpty() {
        assertTrue(AutoCutApplier.toOperations(mock(Uri::class.java), emptyList()).isEmpty())
    }

    @Test
    fun projectIndexJsonRoundTrip() {
        val gson = Gson()
        val original = ProjectIndex(
            listOf(
                LocalProjectMeta(
                    id = "a1",
                    displayName = "Demo",
                    fileName = "a1.lcprj",
                    sourceUri = "file:///tmp/x.mp4",
                    createdAt = 1L,
                    lastModifiedAt = 2L,
                    missingMedia = true
                )
            )
        )
        val restored = gson.fromJson(gson.toJson(original), ProjectIndex::class.java)
        assertEquals(1, restored.items.size)
        assertEquals("a1", restored.items[0].id)
        assertTrue(restored.items[0].missingMedia)
        assertEquals("Demo", restored.items[0].displayName)
    }

    @Test
    fun energyVadDetectsLoudBurst() {
        val sampleRate = 16_000
        val totalSamples = sampleRate // 1s
        val buf = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val sample = if (i in 4000 until 8000) 20_000.toShort() else 50.toShort()
            buf.putShort(sample)
        }
        val pcm = File.createTempFile("vad", ".s16")
        pcm.writeBytes(buf.array())
        try {
            val segs = SileroVadEngine().detect(pcm, null)
            assertTrue(segs.isNotEmpty())
            assertTrue(segs.first().endMs > segs.first().startMs)
        } finally {
            pcm.delete()
        }
    }

    @Test
    fun energyVadEmptyFileReturnsEmpty() {
        val pcm = File.createTempFile("vad_empty", ".s16")
        try {
            assertTrue(SileroVadEngine().detect(pcm, null).isEmpty())
        } finally {
            pcm.delete()
        }
    }
}
