package com.capicut.audiointel.pipeline

import android.content.Context
import android.net.Uri
import com.capicut.audiointel.dsp.AudioChainConfig
import com.capicut.audiointel.dsp.AudioChainStep
import com.capicut.audiointel.dsp.FfmpegDspEngine
import com.capicut.audiointel.models.DeviceBenchmark
import com.capicut.audiointel.models.ModelStore
import com.capicut.audiointel.onnx.DeepFilterNetEngine
import com.capicut.audiointel.onnx.DereverbEngine
import com.capicut.audiointel.vad.SileroVadEngine
import com.capicut.audiointel.vad.SpeechSegment
import com.capicut.audiointel.whisper.Transcript
import com.capicut.audiointel.whisper.WhisperEngine
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PipelineProgress(val step: Int, val total: Int, val label: String)

data class ImproveAudioResult(
    val wav: File,
    val clipped: Boolean,
    val steps: List<String>
)

class AudioIntelligencePipeline(
    context: Context
) {
    private val app = context.applicationContext
    private val ffmpeg = FFmpegRenderEngine(app)
    private val dsp = FfmpegDspEngine(app, ffmpeg)
    private val denoise = DeepFilterNetEngine()
    private val dereverb = DereverbEngine()
    private val vad = SileroVadEngine()
    private val whisper = WhisperEngine()
    private val models = ModelStore(app)
    private val bench = DeviceBenchmark(app)

    suspend fun improveAudio(
        source: Uri,
        jobDir: File,
        config: AudioChainConfig,
        onProgress: (PipelineProgress) -> Unit
    ): Result<ImproveAudioResult> {
        jobDir.mkdirs()
        val applied = mutableListOf<String>()
        val steps = config.enabled.toList()
        val total = steps.size + 1
        onProgress(PipelineProgress(0, total, "Extracting audio"))
        val sourceWav = File(jobDir, "00_source.wav")
        dsp.extractWorkWav(source, sourceWav).getOrElse { return Result.failure(it) }
        val clipped = dsp.detectClipping(sourceWav)
        var current = sourceWav
        var idx = 1
        if (config.isOn(AudioChainStep.HIGHPASS)) {
            onProgress(PipelineProgress(idx, total, "High-pass"))
            val out = File(jobDir, "01_highpass.wav")
            dsp.highpass(current, out).getOrElse { return Result.failure(it) }
            current = out
            applied += "highpass"
            idx++
        }
        if (config.isOn(AudioChainStep.DENOISE)) {
            onProgress(PipelineProgress(idx, total, "Denoise"))
            val out = File(jobDir, "02_denoise.wav")
            val dfnSpec = models.catalog(bench.effectiveTier()).find { it.id == "deepfilter3" }
            val model = dfnSpec?.takeIf { models.isPresent(it) }?.let { models.fileFor(it) }
            if (model == null) {
                // Keep chain usable when ONNX weights are not installed yet.
                current.copyTo(out, overwrite = true)
            } else {
                denoise.process(current, out, model).getOrElse {
                    current.copyTo(out, overwrite = true)
                }
            }
            current = out
            applied += "denoise"
            idx++
        }
        if (config.isOn(AudioChainStep.DEREVERB)) {
            onProgress(PipelineProgress(idx, total, "Dereverb"))
            val out = File(jobDir, "03_dereverb.wav")
            val model = models.catalog(bench.effectiveTier()).find { it.id == "dereverb" }
                ?.let { models.fileFor(it).takeIf { f -> f.exists() && f.length() > 0 } }
            if (model == null) {
                current.copyTo(out, overwrite = true)
            } else {
                dereverb.process(current, out, model).getOrElse {
                    current.copyTo(out, overwrite = true)
                }
            }
            current = out
            applied += "dereverb"
            idx++
        }
        if (config.isOn(AudioChainStep.COMPRESSOR)) {
            onProgress(PipelineProgress(idx, total, "Compressor"))
            val out = File(jobDir, "04_compressor.wav")
            dsp.compressor(current, out).getOrElse { return Result.failure(it) }
            current = out
            applied += "compressor"
            idx++
        }
        if (config.isOn(AudioChainStep.DEESSER)) {
            onProgress(PipelineProgress(idx, total, "De-esser"))
            val out = File(jobDir, "05_deesser.wav")
            dsp.deesser(current, out).getOrElse { return Result.failure(it) }
            current = out
            applied += "deesser"
            idx++
        }
        if (config.isOn(AudioChainStep.LOUDNORM)) {
            onProgress(PipelineProgress(idx, total, "Loudness"))
            val out = File(jobDir, "06_loudnorm.wav")
            dsp.loudnorm(current, out).getOrElse { return Result.failure(it) }
            current = out
            applied += "loudnorm"
            idx++
        }
        val treated = File(jobDir, "treated.wav")
        current.copyTo(treated, overwrite = true)
        File(jobDir, "checkpoint.json").writeText(
            JSONObject()
                .put("steps", JSONArray(applied))
                .put("clipped", clipped)
                .toString()
        )
        return Result.success(ImproveAudioResult(treated, clipped, applied))
    }

    suspend fun autoCut(source: Uri, jobDir: File, onProgress: (PipelineProgress) -> Unit): Result<List<SpeechSegment>> {
        jobDir.mkdirs()
        onProgress(PipelineProgress(0, 2, "Extracting audio"))
        val wav = File(jobDir, "vad_source.wav")
        dsp.extractWorkWav(source, wav).getOrElse { return Result.failure(it) }
        onProgress(PipelineProgress(1, 2, "Detecting speech"))
        val pcm = File(jobDir, "vad.s16")
        dsp.extractPcm16k(wav, pcm).getOrElse { return Result.failure(it) }
        val sileroSpec = models.catalog(bench.effectiveTier()).find { it.id == "silero-vad" }
        val silero = sileroSpec?.takeIf { models.isPresent(it) }?.let { models.fileFor(it) }
        return Result.success(vad.detect(pcm, silero))
    }

    suspend fun transcribe(source: Uri, jobDir: File, onProgress: (PipelineProgress) -> Unit): Result<Transcript> {
        jobDir.mkdirs()
        onProgress(PipelineProgress(0, 2, "Extracting audio"))
        val wav = File(jobDir, "asr_source.wav")
        dsp.extractWorkWav(source, wav).getOrElse { return Result.failure(it) }
        val pcm = File(jobDir, "asr.s16")
        dsp.extractPcm16k(wav, pcm).getOrElse { return Result.failure(it) }
        onProgress(PipelineProgress(1, 2, "Transcribing"))
        val whisperSpec = models.catalog(bench.effectiveTier()).find { it.id.startsWith("whisper") }
        val modelFile = whisperSpec?.takeIf { models.isPresent(it) }?.let { models.fileFor(it) }
        return whisper.transcribe(pcm, modelFile)
    }

    suspend fun exportWav(source: Uri, jobDir: File, onProgress: (PipelineProgress) -> Unit): Result<File> {
        jobDir.mkdirs()
        onProgress(PipelineProgress(0, 1, "Exporting WAV"))
        val out = File(jobDir, "export.wav")
        return dsp.extractWorkWav(source, out)
    }
}
