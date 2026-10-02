package com.capicut.audiointel.whisper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TranscriptWord(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class Transcript(
    val language: String = "pt",
    val words: List<TranscriptWord> = emptyList(),
    val fullText: String = ""
)

/**
 * whisper.cpp JNI binding. Requires a ggml model on disk and the native library.
 */
class WhisperEngine {
    fun transcribe(pcm16k: File, modelFile: File?): Result<Transcript> {
        if (modelFile == null || !modelFile.exists()) {
            return Result.failure(IllegalStateException("Whisper model not downloaded"))
        }
        if (!pcm16k.exists() || pcm16k.length() < 2L) {
            return Result.failure(IllegalStateException("ASR PCM missing"))
        }
        if (!WhisperNative.isAvailable()) {
            return Result.failure(IllegalStateException("whisper.cpp runtime not linked yet"))
        }
        return try {
            val samples = readPcm16Mono(pcm16k)
            val raw = WhisperNative.transcribe(modelFile.absolutePath, samples)
                ?: return Result.failure(IllegalStateException("Whisper returned empty result"))
            val words = parseSegments(raw)
            val full = words.joinToString(" ") { it.text }.trim()
            Result.success(Transcript(language = "pt", words = words, fullText = full))
        } catch (e: UnsatisfiedLinkError) {
            Result.failure(IllegalStateException("whisper.cpp native library missing", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readPcm16Mono(pcm: File): FloatArray {
        val bytes = pcm.readBytes()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shorts.capacity())
        for (i in out.indices) out[i] = shorts.get(i) / 32768f
        return out
    }

    /**
     * Native returns lines: `startMs\tendMs\ttext`
     */
    private fun parseSegments(raw: String): List<TranscriptWord> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val start = parts[0].toLongOrNull() ?: return@mapNotNull null
                val end = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text = parts.drop(2).joinToString("\t").trim()
                if (text.isEmpty()) null else TranscriptWord(start, end, text)
            }
            .toList()
    }
}

internal object WhisperNative {
    @Volatile private var loadAttempted = false
    @Volatile private var available = false

    fun isAvailable(): Boolean {
        if (!loadAttempted) {
            synchronized(this) {
                if (!loadAttempted) {
                    available = runCatching {
                        System.loadLibrary("capicut_whisper")
                        true
                    }.getOrDefault(false)
                    loadAttempted = true
                }
            }
        }
        return available
    }

    @JvmStatic
    external fun transcribe(modelPath: String, samples: FloatArray): String?
}
