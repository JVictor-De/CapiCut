package com.capicut.audiointel.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.capicut.audiointel.dsp.AudioChainDefaults
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

data class SpeechSegment(val startMs: Long, val endMs: Long)

/**
 * Silero VAD via ONNX Runtime when [sileroModel] exists; otherwise energy VAD.
 */
class SileroVadEngine {
    fun detect(pcm16k: File, sileroModel: File? = null): List<SpeechSegment> {
        if (sileroModel != null && sileroModel.exists() && sileroModel.length() > 0L) {
            runCatching { sileroVad(pcm16k, sileroModel) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() || pcm16k.length() > 0L }
                ?.let { return it }
        }
        return energyVad(pcm16k)
    }

    private fun sileroVad(pcm: File, model: File): List<SpeechSegment> {
        val samples = readPcm16Mono(pcm)
        if (samples.isEmpty()) return emptyList()
        val sampleRate = AudioChainDefaults.ASR_SAMPLE_RATE_HZ
        val window = 512 // Silero v5 window @ 16 kHz
        val hop = 512
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        env.createSession(model.absolutePath, opts).use { session ->
            val inputNames = session.inputNames
            val hasState = inputNames.any { it.contains("state", ignoreCase = true) }
            val srName = inputNames.firstOrNull { it.contains("sr", ignoreCase = true) }
            val audioName = inputNames.firstOrNull {
                it.contains("input", ignoreCase = true) || it == "input" || it.contains("audio")
            } ?: inputNames.first()

            var state: Array<Array<FloatArray>>? = if (hasState) {
                Array(2) { Array(1) { FloatArray(128) } }
            } else null
            val stateName = inputNames.firstOrNull { it.contains("state", ignoreCase = true) }

            val speechFlags = BooleanArray((samples.size + hop - 1) / hop)
            var frameIdx = 0
            var offset = 0
            while (offset + window <= samples.size) {
                val chunk = FloatArray(window)
                System.arraycopy(samples, offset, chunk, 0, window)
                val feeds = HashMap<String, OnnxTensor>()
                try {
                    feeds[audioName] = OnnxTensor.createTensor(
                        env,
                        FloatBuffer.wrap(chunk),
                        longArrayOf(1, window.toLong())
                    )
                    if (srName != null) {
                        feeds[srName] = OnnxTensor.createTensor(env, longArrayOf(sampleRate.toLong()))
                    }
                    if (state != null && stateName != null) {
                        feeds[stateName] = OnnxTensor.createTensor(env, state)
                    }
                    session.run(feeds).use { result ->
                        val out0 = result.get(0).value
                        val prob = when (out0) {
                            is FloatArray -> out0.firstOrNull() ?: 0f
                            is Array<*> -> {
                                val row = out0.firstOrNull()
                                when (row) {
                                    is FloatArray -> row.firstOrNull() ?: 0f
                                    is Array<*> -> (row.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f
                                    else -> 0f
                                }
                            }
                            else -> 0f
                        }
                        speechFlags[frameIdx] = prob >= 0.5f
                        if (hasState && result.size() > 1) {
                            @Suppress("UNCHECKED_CAST")
                            val next = result.get(1).value as? Array<Array<FloatArray>>
                            if (next != null) state = next
                        }
                    }
                } finally {
                    feeds.values.forEach { it.close() }
                }
                frameIdx++
                offset += hop
            }

            val windowMs = (window * 1000L) / sampleRate
            val raw = mutableListOf<SpeechSegment>()
            var start = -1
            for (i in speechFlags.indices) {
                if (speechFlags[i] && start < 0) start = i
                if (!speechFlags[i] && start >= 0) {
                    raw.add(SpeechSegment(start.toLong() * windowMs, i.toLong() * windowMs))
                    start = -1
                }
            }
            if (start >= 0) {
                raw.add(SpeechSegment(start.toLong() * windowMs, speechFlags.size.toLong() * windowMs))
            }
            return mergePad(raw)
        }
    }

    private fun energyVad(pcm: File): List<SpeechSegment> {
        if (!pcm.exists()) return emptyList()
        val bytes = pcm.readBytes()
        if (bytes.size < 2) return emptyList()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleRate = AudioChainDefaults.ASR_SAMPLE_RATE_HZ
        val windowMs = 20
        val samplesPerWindow = (sampleRate * windowMs) / 1000
        val numWindows = shorts.capacity() / samplesPerWindow
        if (numWindows == 0) return emptyList()
        val energies = FloatArray(numWindows)
        for (i in 0 until numWindows) {
            var sum = 0.0
            for (j in 0 until samplesPerWindow) {
                val s = shorts.get(i * samplesPerWindow + j).toDouble()
                sum += s * s
            }
            energies[i] = sqrt(sum / samplesPerWindow).toFloat()
        }
        val sorted = energies.sorted()
        val median = sorted[sorted.size / 2]
        val threshold = (median * 2.5f).coerceAtLeast(400f)

        val raw = mutableListOf<SpeechSegment>()
        var start = -1
        for (i in 0 until numWindows) {
            val speech = energies[i] >= threshold
            if (speech && start < 0) start = i
            if (!speech && start >= 0) {
                raw.add(SpeechSegment(start.toLong() * windowMs, i.toLong() * windowMs))
                start = -1
            }
        }
        if (start >= 0) {
            raw.add(SpeechSegment(start.toLong() * windowMs, numWindows.toLong() * windowMs))
        }
        return mergePad(raw)
    }

    private fun mergePad(raw: List<SpeechSegment>): List<SpeechSegment> {
        val pad = AudioChainDefaults.VAD_PAD_MS
        val minSilence = AudioChainDefaults.VAD_MIN_SILENCE_MS
        val padded = raw.map {
            SpeechSegment((it.startMs - pad).coerceAtLeast(0), it.endMs + pad)
        }
        if (padded.isEmpty()) return emptyList()
        val merged = mutableListOf<SpeechSegment>()
        var cur = padded.first()
        for (i in 1 until padded.size) {
            val next = padded[i]
            if (next.startMs - cur.endMs < minSilence) {
                cur = SpeechSegment(cur.startMs, next.endMs)
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged.filter { it.endMs - it.startMs >= 120 }
    }

    private fun readPcm16Mono(pcm: File): FloatArray {
        val bytes = pcm.readBytes()
        if (bytes.size < 2) return FloatArray(0)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shorts.capacity())
        for (i in out.indices) {
            out[i] = shorts.get(i) / 32768f
        }
        return out
    }
}
