package com.capicut.audiointel.dsp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

class FfmpegDspEngine(
    private val context: Context,
    private val ffmpeg: FFmpegRenderEngine
) {
    companion object {
        private const val TAG = "FfmpegDspEngine"
    }

    suspend fun extractWorkWav(source: Uri, output: File): Result<File> {
        val input = resolveInputPath(source)
            ?: return Result.failure(IllegalStateException("Cannot open source media"))
        val cmd = "-y -i \"$input\" -vn -ac 1 -ar ${AudioChainDefaults.WORK_SAMPLE_RATE_HZ} " +
            "-c:a pcm_s16le \"${output.absolutePath}\""
        return runCommand(cmd, output)
    }

    suspend fun extractPcm16k(source: File, output: File): Result<File> {
        if (!source.exists() || source.length() == 0L) {
            return Result.failure(IllegalStateException("Source audio file missing"))
        }
        val cmd = "-y -i \"${source.absolutePath}\" -vn -ac 1 -ar ${AudioChainDefaults.ASR_SAMPLE_RATE_HZ} " +
            "-f s16le \"${output.absolutePath}\""
        return runCommand(cmd, output)
    }

    suspend fun highpass(input: File, output: File): Result<File> {
        val poles = if (AudioChainDefaults.HIGHPASS_POLES >= 2) {
            "highpass=f=${AudioChainDefaults.HIGHPASS_FREQ_HZ},highpass=f=${AudioChainDefaults.HIGHPASS_FREQ_HZ}"
        } else {
            "highpass=f=${AudioChainDefaults.HIGHPASS_FREQ_HZ}"
        }
        return runFilter(input, output, poles)
    }

    suspend fun compressor(input: File, output: File): Result<File> {
        val filter = "acompressor=threshold=${dbToLinear(AudioChainDefaults.COMPRESSOR_THRESHOLD_DB)}:" +
            "ratio=${fmt(AudioChainDefaults.COMPRESSOR_RATIO)}:" +
            "attack=${fmt(AudioChainDefaults.COMPRESSOR_ATTACK_MS)}:" +
            "release=${fmt(AudioChainDefaults.COMPRESSOR_RELEASE_MS)}"
        return runFilter(input, output, filter)
    }

    suspend fun deesser(input: File, output: File): Result<File> {
        // Approximate de-esser with a narrow cut around sibilance until a dedicated filter is available.
        val filter = "equalizer=f=${AudioChainDefaults.DEESSER_FREQ_HZ}:t=q:w=1.2:g=-8"
        return runFilter(input, output, filter)
    }

    suspend fun loudnorm(input: File, output: File): Result<File> {
        val filter = "loudnorm=I=${fmt(AudioChainDefaults.LOUDNORM_I)}:" +
            "TP=${fmt(AudioChainDefaults.LOUDNORM_TP)}:" +
            "LRA=${fmt(AudioChainDefaults.LOUDNORM_LRA)}"
        return runFilter(input, output, filter)
    }

    /**
     * Detects near-full-scale peaks in PCM/WAV (s16le).
     * For WAV, skips RIFF chunks until the `data` payload.
     */
    fun detectClipping(wavOrPcm: File): Boolean {
        if (!wavOrPcm.exists() || wavOrPcm.length() < 4) return false
        return try {
            FileInputStream(wavOrPcm).use { ins ->
                val header = ByteArray(12)
                if (ins.read(header) < 4) return false
                val isWav = header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())
                if (isWav) {
                    // Already consumed 12 bytes (RIFF....WAVE). Walk chunks to `data`.
                    while (true) {
                        val chunkHeader = ByteArray(8)
                        val n = ins.read(chunkHeader)
                        if (n < 8) return false
                        val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                        val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                        if (id == "data") break
                        var remaining = size + (size and 1) // pad to even
                        while (remaining > 0) {
                            val skipped = ins.skip(remaining)
                            if (skipped <= 0) return false
                            remaining -= skipped
                        }
                    }
                }
                val buf = ByteArray(8192)
                val threshold = (Short.MAX_VALUE * AudioChainDefaults.CLIP_PEAK_THRESHOLD).toInt()
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    val usable = n - n % 2
                    if (usable <= 0) continue
                    val shorts = ByteBuffer.wrap(buf, 0, usable).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shorts.hasRemaining()) {
                        if (abs(shorts.get().toInt()) >= threshold) return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Clipping detection failed: ${e.message}")
            false
        }
    }

    private fun resolveInputPath(source: Uri): String? {
        return when (source.scheme) {
            "file", null -> source.path?.takeIf { it.isNotBlank() && File(it).exists() }
            "content" -> {
                try {
                    val saf = FFmpegKitConfig.getSafParameterForRead(context, source)
                    if (!saf.isNullOrBlank()) return saf
                } catch (e: Exception) {
                    Log.w(TAG, "SAF resolve failed, copying: ${e.message}")
                }
                copyContentToCache(source)
            }
            else -> source.path
        }
    }

    private fun copyContentToCache(uri: Uri): String? {
        return try {
            val dest = File(context.cacheDir, "capicut_in_${System.currentTimeMillis()}.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (dest.length() == 0L) {
                dest.delete()
                null
            } else {
                dest.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyContentToCache failed: ${e.message}", e)
            null
        }
    }

    private suspend fun runFilter(input: File, output: File, filter: String): Result<File> {
        if (!input.exists() || input.length() == 0L) {
            return Result.failure(IllegalStateException("Filter input missing: ${input.name}"))
        }
        val cmd = "-y -i \"${input.absolutePath}\" -af \"$filter\" -ac 1 " +
            "-ar ${AudioChainDefaults.WORK_SAMPLE_RATE_HZ} -c:a pcm_s16le \"${output.absolutePath}\""
        return runCommand(cmd, output)
    }

    private suspend fun runCommand(cmd: String, output: File): Result<File> {
        return when (val r = ffmpeg.executeCommand(cmd)) {
            is FFmpegRenderEngine.RenderResult.Success -> {
                if (!output.exists() || output.length() == 0L) {
                    Result.failure(IllegalStateException("FFmpeg produced empty output"))
                } else {
                    Result.success(output)
                }
            }
            is FFmpegRenderEngine.RenderResult.Failure ->
                Result.failure(IllegalStateException(summarizeFfmpegError(r.error)))
            is FFmpegRenderEngine.RenderResult.Cancelled ->
                Result.failure(IllegalStateException("Cancelled"))
        }
    }

    private fun summarizeFfmpegError(raw: String): String {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("Error", ignoreCase = true) || it.contains("failed", ignoreCase = true) }
        return line?.take(200) ?: "FFmpeg processing failed"
    }

    private fun dbToLinear(db: Double): String {
        val v = Math.pow(10.0, db / 20.0)
        return fmt(v)
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
}
