package com.capicut.audiointel

import android.content.Intent
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.capicut.audiointel.dsp.AudioChainConfig
import com.capicut.audiointel.dsp.FfmpegDspEngine
import com.capicut.audiointel.pipeline.AudioIntelligencePipeline
import com.capicut.audiointel.project.LocalProjectRepository
import com.capicut.audiointel.service.MediaProcessingService
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CapicutInstrumentedAudioTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): File {
        val out = File(context.cacheDir, name)
        context.assets.open(name).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("asset $name missing", out.exists() && out.length() > 0)
        return out
    }

    @Test
    fun ffmpegExtractWorkWavProducesNonEmptyFile() = runBlocking {
        val wav = copyAsset("sample_tone.wav")
        val jobDir = File(context.cacheDir, "instr_ffmpeg").apply { mkdirs() }
        val out = File(jobDir, "work.wav")
        val ffmpeg = FFmpegRenderEngine(context)
        val dsp = FfmpegDspEngine(context, ffmpeg)
        val result = dsp.extractWorkWav(Uri.fromFile(wav), out)
        result.fold(
            onSuccess = { assertTrue(it.exists() && it.length() > 44) },
            onFailure = { fail("FFmpeg extract failed: ${it.message}") }
        )
    }

    @Test
    fun pipelineExportWavWithRealMedia() = runBlocking {
        val wav = copyAsset("sample_tone.wav")
        val jobDir = File(context.cacheDir, "instr_export").apply {
            deleteRecursively()
            mkdirs()
        }
        val pipeline = AudioIntelligencePipeline(context)
        val result = pipeline.exportWav(Uri.fromFile(wav), jobDir) { }
        result.fold(
            onSuccess = { assertTrue(it.exists() && it.length() > 44) },
            onFailure = { fail("exportWav failed: ${it.message}") }
        )
    }

    @Test
    fun improveAudioDefaultChainWithoutOnnx() = runBlocking {
        val wav = copyAsset("sample_tone.wav")
        val jobDir = File(context.cacheDir, "instr_improve").apply {
            deleteRecursively()
            mkdirs()
        }
        val pipeline = AudioIntelligencePipeline(context)
        val result = pipeline.improveAudio(Uri.fromFile(wav), jobDir, AudioChainConfig()) { }
        result.fold(
            onSuccess = {
                assertTrue(it.wav.exists() && it.wav.length() > 44)
                assertTrue(it.steps.isNotEmpty())
            },
            onFailure = { fail("improveAudio failed: ${it.message}") }
        )
    }

    @Test
    fun localProjectAutosaveWithRealMediaUri() {
        val wav = copyAsset("sample_tone.wav")
        val repo = LocalProjectRepository(context)
        val recipe = EditRecipe(
            projectName = "instr-autosave",
            sourceUri = Uri.fromFile(wav),
            sourceName = wav.name,
            lastModifiedAt = System.currentTimeMillis()
        )
        val meta = repo.save(recipe)
        assertNotNull(meta.id)
        assertTrue(repo.projectFile(meta.id).exists())
        assertFalse(meta.missingMedia)
        val listed = repo.list().any { it.id == meta.id }
        assertTrue(listed)
        repo.delete(meta.id)
    }

    @Test
    fun mediaProcessingServiceCancelBroadcastsFailure() {
        val wav = copyAsset("sample_tone.wav")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val lbm = LocalBroadcastManager.getInstance(context)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                if (intent?.action == MediaProcessingService.ACTION_FAILURE) {
                    failure.set(intent.getStringExtra(MediaProcessingService.EXTRA_ERROR))
                    latch.countDown()
                }
            }
        }
        lbm.registerReceiver(receiver, android.content.IntentFilter(MediaProcessingService.ACTION_FAILURE))
        try {
            val jobId = "instr-cancel-${System.currentTimeMillis()}"
            val start = Intent(context, MediaProcessingService::class.java).apply {
                putExtra(MediaProcessingService.EXTRA_JOB_ID, jobId)
                putExtra(MediaProcessingService.EXTRA_JOB_TYPE, MediaProcessingService.JOB_IMPROVE_AUDIO)
                putExtra(MediaProcessingService.EXTRA_SOURCE_URI, Uri.fromFile(wav).toString())
            }
            context.startService(start)
            // Give the service a moment to enqueue, then cancel.
            Thread.sleep(200)
            val cancel = Intent(context, MediaProcessingService::class.java).apply {
                action = MediaProcessingService.ACTION_CANCEL
                putExtra(MediaProcessingService.EXTRA_JOB_ID, jobId)
                putExtra(MediaProcessingService.EXTRA_JOB_TYPE, MediaProcessingService.JOB_IMPROVE_AUDIO)
            }
            context.startService(cancel)
            assertTrue("cancel broadcast timeout", latch.await(20, TimeUnit.SECONDS))
            assertNotNull(failure.get())
        } finally {
            lbm.unregisterReceiver(receiver)
        }
    }
}
