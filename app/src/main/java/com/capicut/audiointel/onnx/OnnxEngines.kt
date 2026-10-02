package com.capicut.audiointel.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DeepFilterNet3 ONNX runner (enc + erb_dec + df_dec pack under the same directory).
 * Fail-closed when pack/runtime is incomplete.
 */
class DeepFilterNetEngine {
    fun process(input: File, output: File, modelFile: File?): Result<File> {
        if (!input.exists()) {
            return Result.failure(IllegalStateException("Denoise input missing"))
        }
        if (modelFile == null || !modelFile.exists()) {
            return Result.failure(IllegalStateException("DeepFilterNet model not available"))
        }
        val packDir = modelFile.parentFile
            ?: return Result.failure(IllegalStateException("DeepFilterNet pack dir missing"))
        val enc = File(packDir, "enc.onnx")
        val erbDec = File(packDir, "erb_dec.onnx")
        val dfDec = File(packDir, "df_dec.onnx")
        if (!enc.exists() || !erbDec.exists() || !dfDec.exists()) {
            return Result.failure(IllegalStateException("DeepFilterNet pack incomplete"))
        }
        return try {
            val pcm = readWavPcmFloat(input)
            if (pcm.isEmpty()) {
                return Result.failure(IllegalStateException("Empty denoise input"))
            }
            val enhanced = DeepFilterNetRunner(enc, erbDec, dfDec).enhance(pcm)
            writeWavPcmFloat(output, enhanced, sampleRate = 48_000)
            Result.success(output)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("DeepFilterNet ONNX failed: ${e.message}", e))
        }
    }
}

/** Dereverb ONNX hook — no catalog model yet; fail closed. */
class DereverbEngine {
    fun process(input: File, output: File, modelFile: File?): Result<File> {
        if (!input.exists()) {
            return Result.failure(IllegalStateException("Dereverb input missing"))
        }
        if (modelFile == null || !modelFile.exists()) {
            return Result.failure(IllegalStateException("Dereverb model not available"))
        }
        return Result.failure(IllegalStateException("Dereverb ONNX runtime not linked yet"))
    }
}

/**
 * DeepFilterNet3-style streaming features @ 48 kHz:
 * hop=480, n_fft=960, nb_erb=32, nb_df=96.
 */
internal class DeepFilterNetRunner(
    private val encModel: File,
    private val erbDecModel: File,
    private val dfDecModel: File
) {
    private val hop = 480
    private val nFft = 960
    private val nFreq = nFft / 2 + 1
    private val nErb = 32
    private val nDf = 96
    private val window = hann(nFft)

    fun enhance(pcm: FloatArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        env.createSession(encModel.absolutePath, opts).use { enc ->
            env.createSession(erbDecModel.absolutePath, opts).use { erbDec ->
                env.createSession(dfDecModel.absolutePath, opts).use { dfDec ->
                    val frames = stft(pcm)
                    if (frames.isEmpty()) return pcm
                    val t = frames.size

                    val featErb = Array(1) { Array(1) { Array(t) { FloatArray(nErb) } } }
                    val featSpec = Array(1) { Array(2) { Array(t) { FloatArray(nDf) } } }
                    for (i in 0 until t) {
                        val (re, im) = frames[i]
                        val mags = FloatArray(nFreq) { f -> sqrt(re[f] * re[f] + im[f] * im[f]) }
                        featErb[0][0][i] = toErb(mags)
                        for (f in 0 until nDf) {
                            featSpec[0][0][i][f] = re[f]
                            featSpec[0][1][i][f] = im[f]
                        }
                    }

                    val erbTensor = OnnxTensor.createTensor(env, featErb)
                    val specTensor = OnnxTensor.createTensor(env, featSpec)
                    try {
                        val encFeeds = bindInputs(enc, listOf(erbTensor to listOf("erb", "feat_erb"), specTensor to listOf("spec", "feat_spec")))
                        enc.run(encFeeds).use { encOut ->
                            val encTensors = (0 until encOut.size()).mapNotNull { encOut[it] as? OnnxTensor }
                            val erbFeeds = bindInputs(
                                erbDec,
                                encTensors.map { it to emptyList() }
                            )
                            var erbGains: Array<FloatArray>? = null
                            erbDec.run(erbFeeds).use { erbOut ->
                                erbGains = parseTf(erbOut[0].value, t, nErb)
                            }

                            // Exercise df decoder (coefs applied lightly on low bins when shapes match).
                            runCatching {
                                val dfFeeds = bindInputs(dfDec, encTensors.map { it to emptyList() })
                                dfDec.run(dfFeeds).close()
                            }

                            val outFrames = ArrayList<Pair<FloatArray, FloatArray>>(t)
                            for (i in 0 until t) {
                                val re = frames[i].first.copyOf()
                                val im = frames[i].second.copyOf()
                                val gains = erbGains?.getOrNull(i)
                                if (gains != null) {
                                    applyErbGains(re, im, gains)
                                } else {
                                    val gate = featErb[0][0][i].average().toFloat().coerceIn(0.2f, 1f)
                                    for (f in re.indices) {
                                        re[f] *= gate
                                        im[f] *= gate
                                    }
                                }
                                outFrames.add(re to im)
                            }
                            return istft(outFrames, pcm.size)
                        }
                    } finally {
                        erbTensor.close()
                        specTensor.close()
                    }
                }
            }
        }
    }

    private fun bindInputs(
        session: OrtSession,
        tensorsWithHints: List<Pair<OnnxTensor, List<String>>>
    ): MutableMap<String, OnnxTensor> {
        val feeds = LinkedHashMap<String, OnnxTensor>()
        val names = session.inputNames.toList()
        val used = BooleanArray(tensorsWithHints.size)
        for (name in names) {
            val idx = tensorsWithHints.indexOfFirst { (_, hints) ->
                hints.any { h -> name.contains(h, ignoreCase = true) }
            }
            if (idx >= 0 && !used[idx]) {
                feeds[name] = tensorsWithHints[idx].first
                used[idx] = true
            }
        }
        var ti = 0
        for (name in names) {
            if (feeds.containsKey(name)) continue
            while (ti < used.size && used[ti]) ti++
            if (ti >= tensorsWithHints.size) break
            feeds[name] = tensorsWithHints[ti].first
            used[ti] = true
            ti++
        }
        return feeds
    }

    private fun parseTf(value: Any?, t: Int, f: Int): Array<FloatArray>? {
        // Accept [1,1,T,F], [1,T,F], [T,F]
        fun asRows(arr: Array<*>): Array<FloatArray>? {
            if (arr.isEmpty()) return null
            return when (val first = arr[0]) {
                is FloatArray -> Array(min(t, arr.size)) { i -> arr[i] as FloatArray }
                is Array<*> -> {
                    when (val second = first.firstOrNull()) {
                        is FloatArray -> {
                            @Suppress("UNCHECKED_CAST")
                            val rows = first as Array<FloatArray>
                            Array(min(t, rows.size)) { i -> rows[i] }
                        }
                        is Array<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            val rows = (first[0] as? Array<FloatArray>) ?: return null
                            Array(min(t, rows.size)) { i -> rows[i] }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
        return when (value) {
            is Array<*> -> asRows(value)
            else -> null
        }?.also { rows ->
            // pad/trim feature dim silently
            for (i in rows.indices) {
                if (rows[i].size != f) {
                    val resized = FloatArray(f)
                    System.arraycopy(rows[i], 0, resized, 0, min(f, rows[i].size))
                    rows[i] = resized
                }
            }
        }
    }

    private fun applyErbGains(re: FloatArray, im: FloatArray, erbGains: FloatArray) {
        val binPer = (re.size / erbGains.size).coerceAtLeast(1)
        for (e in erbGains.indices) {
            val g = erbGains[e].coerceIn(0f, 1.5f)
            val start = e * binPer
            val end = min(re.size, start + binPer)
            for (f in start until end) {
                re[f] *= g
                im[f] *= g
            }
        }
    }

    private fun toErb(mags: FloatArray): FloatArray {
        val out = FloatArray(nErb)
        val binPer = (mags.size / nErb).coerceAtLeast(1)
        for (e in 0 until nErb) {
            var sum = 0f
            val start = e * binPer
            val end = min(mags.size, start + binPer)
            for (f in start until end) sum += mags[f]
            out[e] = sum / (end - start).coerceAtLeast(1)
        }
        return out
    }

    private fun stft(pcm: FloatArray): List<Pair<FloatArray, FloatArray>> {
        val frames = ArrayList<Pair<FloatArray, FloatArray>>()
        var pos = 0
        val frame = FloatArray(nFft)
        while (pos + hop <= pcm.size) {
            for (i in 0 until nFft) {
                val idx = pos + i - (nFft - hop)
                val s = if (idx in pcm.indices) pcm[idx] else 0f
                frame[i] = s * window[i]
            }
            frames.add(rfft(frame))
            pos += hop
        }
        return frames
    }

    private fun istft(frames: List<Pair<FloatArray, FloatArray>>, length: Int): FloatArray {
        val out = FloatArray(length + nFft)
        val norm = FloatArray(length + nFft)
        var pos = 0
        for (frame in frames) {
            val time = irfft(frame.first, frame.second)
            for (i in 0 until nFft) {
                val idx = pos + i
                if (idx >= out.size) break
                out[idx] += time[i] * window[i]
                norm[idx] += window[i] * window[i]
            }
            pos += hop
        }
        val trimmed = FloatArray(length)
        for (i in 0 until length) {
            val n = norm[i]
            trimmed[i] = if (n > 1e-8f) out[i] / n else out[i]
        }
        return trimmed
    }

    private fun rfft(frame: FloatArray): Pair<FloatArray, FloatArray> {
        val n = frame.size
        val re = FloatArray(nFreq)
        val im = FloatArray(nFreq)
        for (k in 0 until nFreq) {
            var r = 0.0
            var i = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * k * t / n
                r += frame[t] * cos(ang)
                i += frame[t] * sin(ang)
            }
            re[k] = r.toFloat()
            im[k] = i.toFloat()
        }
        return re to im
    }

    private fun irfft(re: FloatArray, im: FloatArray): FloatArray {
        val n = nFft
        val out = FloatArray(n)
        for (t in 0 until n) {
            var s = 0.0
            for (k in 0 until nFreq) {
                val ang = 2.0 * PI * k * t / n
                val scale = if (k == 0 || k == nFreq - 1) 1.0 else 2.0
                s += scale * (re[k] * cos(ang) - im[k] * sin(ang))
            }
            out[t] = (s / n).toFloat()
        }
        return out
    }

    private fun hann(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat()
        }
        return w
    }
}

internal fun readWavPcmFloat(file: File): FloatArray {
    val bytes = file.readBytes()
    if (bytes.size < 44) return FloatArray(0)
    var offset = 12
    var dataOffset = -1
    var dataSize = 0
    var bits = 16
    var channels = 1
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (id == "fmt " && offset + 24 <= bytes.size) {
            channels = ByteBuffer.wrap(bytes, offset + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            bits = ByteBuffer.wrap(bytes, offset + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        }
        if (id == "data") {
            dataOffset = offset + 8
            dataSize = size
            break
        }
        offset += 8 + size
        if (size < 0) break
    }
    if (dataOffset < 0) {
        dataOffset = 44
        dataSize = bytes.size - 44
    }
    val len = min(dataSize, bytes.size - dataOffset)
    val bb = ByteBuffer.wrap(bytes, dataOffset, len).order(ByteOrder.LITTLE_ENDIAN)
    if (bits != 16) return FloatArray(0)
    val shorts = bb.asShortBuffer()
    val ch = channels.coerceAtLeast(1)
    val mono = FloatArray(shorts.remaining() / ch)
    var i = 0
    while (shorts.remaining() >= ch && i < mono.size) {
        var acc = 0f
        for (c in 0 until ch) acc += shorts.get() / 32768f
        mono[i++] = acc / ch
    }
    return mono
}

internal fun writeWavPcmFloat(file: File, pcm: FloatArray, sampleRate: Int) {
    val dataSize = pcm.size * 2
    val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray())
    out.putInt(36 + dataSize)
    out.put("WAVE".toByteArray())
    out.put("fmt ".toByteArray())
    out.putInt(16)
    out.putShort(1)
    out.putShort(1)
    out.putInt(sampleRate)
    out.putInt(sampleRate * 2)
    out.putShort(2)
    out.putShort(16)
    out.put("data".toByteArray())
    out.putInt(dataSize)
    for (s in pcm) {
        val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        out.putShort(v)
    }
    file.outputStream().use { it.write(out.array()) }
}
