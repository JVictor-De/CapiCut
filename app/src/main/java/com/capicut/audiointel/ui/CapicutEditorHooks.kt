package com.capicut.audiointel.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.capicut.audiointel.project.LocalProjectRepository
import com.capicut.audiointel.service.MediaProcessingService
import com.capicut.audiointel.transcript.FillerWordDetector
import com.capicut.audiointel.transcript.TranscriptExporter
import com.capicut.audiointel.vad.AutoCutApplier
import com.capicut.audiointel.vad.SpeechSegment
import com.capicut.audiointel.video.AutoVideoTreat
import com.capicut.audiointel.video.FaceReframeEngine
import com.capicut.audiointel.whisper.Transcript
import com.capicut.audiointel.whisper.TranscriptWord
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.commands.AddOperationCommand
import com.tharunbirla.librecuts.commands.MutateListCommand
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/**
 * Wires CapiCut auto buttons, progress banner, autosave, and result → EditOperations.
 */
class CapicutEditorHooks(
    private val activity: AppCompatActivity,
    private val viewModel: VideoEditingViewModel,
    private val root: View
) {
    private val repo = LocalProjectRepository(activity)
    private var localProjectId: String? = null
    private val progressBanner: TextView? = root.findViewById(R.id.tvCapicutProgress)
    private var autosaveJob: Job? = null
    private var busy = false
    private var lastTranscript: Transcript? = null

    private val notificationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* one-shot ask */ }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaProcessingService.ACTION_PROGRESS -> {
                    val label = intent.getStringExtra(MediaProcessingService.EXTRA_LABEL) ?: ""
                    val p = intent.getIntExtra(MediaProcessingService.EXTRA_PROGRESS, 0)
                    showProgress("$label $p%")
                }
                MediaProcessingService.ACTION_SUCCESS -> {
                    busy = false
                    hideProgress()
                    handleSuccess(intent)
                }
                MediaProcessingService.ACTION_FAILURE -> {
                    busy = false
                    hideProgress()
                    val err = intent.getStringExtra(MediaProcessingService.EXTRA_ERROR)
                        ?: activity.getString(R.string.capicut_unknown_error)
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun onCreate() {
        bindButton(R.id.btnCapicutImproveAudio) { startJob(MediaProcessingService.JOB_IMPROVE_AUDIO) }
        bindButton(R.id.btnCapicutAutoCut) { startJob(MediaProcessingService.JOB_AUTO_CUT) }
        bindButton(R.id.btnCapicutTranscribe) { startJob(MediaProcessingService.JOB_TRANSCRIBE) }
        bindButton(R.id.btnCapicutAutoVideo) { applyAutoVideoLocally() }
        bindButton(R.id.btnCapicutExportWav) { startJob(MediaProcessingService.JOB_EXPORT_WAV) }

        val filter = IntentFilter().apply {
            addAction(MediaProcessingService.ACTION_PROGRESS)
            addAction(MediaProcessingService.ACTION_SUCCESS)
            addAction(MediaProcessingService.ACTION_FAILURE)
        }
        LocalBroadcastManager.getInstance(activity).registerReceiver(receiver, filter)
        startAutosave()
        maybeAskNotifications()
    }

    fun onDestroy() {
        autosaveJob?.cancel()
        try {
            LocalBroadcastManager.getInstance(activity).unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        persistNow()
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bindButton(id: Int, action: () -> Unit) {
        root.findViewById<View>(id)?.setOnClickListener { action() }
    }

    private fun startJob(type: String) {
        if (busy) {
            Toast.makeText(activity, R.string.capicut_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = viewModel.project.value?.sourceUri
        if (uri == null) {
            Toast.makeText(activity, R.string.capicut_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        showProgress(activity.getString(R.string.capicut_processing))
        if (!notificationsGranted()) {
            Toast.makeText(activity, R.string.capicut_notifications_denied, Toast.LENGTH_LONG).show()
        }
        try {
            MediaProcessingService.start(activity, type, uri)
        } catch (e: Exception) {
            busy = false
            hideProgress()
            Toast.makeText(activity, e.message ?: activity.getString(R.string.capicut_unknown_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun applyAutoVideoLocally() {
        if (viewModel.project.value == null) {
            Toast.makeText(activity, R.string.capicut_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.executeCommand(FaceReframeEngine().crop916())
        viewModel.executeCommand(AutoVideoTreat().exposureLift())
        Toast.makeText(activity, R.string.capicut_video_applied, Toast.LENGTH_SHORT).show()
    }

    private fun handleSuccess(intent: Intent) {
        when (intent.getStringExtra(MediaProcessingService.EXTRA_JOB_TYPE)) {
            MediaProcessingService.JOB_IMPROVE_AUDIO -> applyTreatedAudio(intent)
            MediaProcessingService.JOB_AUTO_CUT -> applyAutoCut(intent)
            MediaProcessingService.JOB_TRANSCRIBE -> applyTranscript(intent)
            MediaProcessingService.JOB_EXPORT_WAV -> {
                val path = intent.getStringExtra(MediaProcessingService.EXTRA_OUTPUT_WAV) ?: return
                copyWavToMusic(File(path))
            }
        }
    }

    private fun applyTreatedAudio(intent: Intent) {
        val path = intent.getStringExtra(MediaProcessingService.EXTRA_OUTPUT_WAV) ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(activity, R.string.capicut_audio_failed, Toast.LENGTH_LONG).show()
            return
        }
        val wavUri = Uri.fromFile(file)
        viewModel.executeCommand(
            AddOperationCommand(
                EditOperation.AddBackgroundAudio(
                    audioUri = wavUri,
                    removeOriginalAudio = true,
                    volume = 1.0f,
                    startTimeMs = 0L
                ),
                "CapiCut treated audio"
            )
        )
        if (intent.getBooleanExtra(MediaProcessingService.EXTRA_CLIPPED, false)) {
            Toast.makeText(activity, R.string.capicut_clipping_warning, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(activity, R.string.capicut_audio_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAutoCut(intent: Intent) {
        val json = intent.getStringExtra(MediaProcessingService.EXTRA_SEGMENTS_JSON) ?: return
        val arr = JSONArray(json)
        val segments = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(SpeechSegment(o.optLong("startMs"), o.optLong("endMs")))
            }
        }
        val source = viewModel.project.value?.sourceUri
        if (source == null || segments.isEmpty()) {
            Toast.makeText(activity, R.string.capicut_no_speech, Toast.LENGTH_SHORT).show()
            return
        }
        val ops = AutoCutApplier.toOperations(source, segments)
        if (ops.isEmpty()) {
            Toast.makeText(activity, R.string.capicut_no_speech, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.capicut_auto_cut)
            .setMessage(activity.getString(R.string.capicut_auto_cut_review, segments.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.executeCommand(MutateListCommand("CapiCut auto cut") { current ->
                    val without = current.filterNot {
                        it is EditOperation.Trim || it is EditOperation.Merge
                    }
                    without + ops
                })
                Toast.makeText(activity, R.string.capicut_auto_cut_applied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyTranscript(intent: Intent) {
        val text = intent.getStringExtra(MediaProcessingService.EXTRA_TRANSCRIPT_TEXT).orEmpty()
        val wordsJson = intent.getStringExtra(MediaProcessingService.EXTRA_TRANSCRIPT_WORDS).orEmpty()
        val words = parseWords(wordsJson)
        if (text.isBlank() && words.isEmpty()) {
            Toast.makeText(activity, R.string.capicut_whisper_missing, Toast.LENGTH_LONG).show()
            return
        }
        val transcript = Transcript(fullText = text, words = words)
        lastTranscript = transcript
        val cues = if (words.isNotEmpty()) {
            TranscriptExporter.wordsToCues(words)
        } else {
            listOf(com.tharunbirla.librecuts.models.SubtitleCue(0, 3_000, text))
        }
        val srt = TranscriptExporter.toSrt(cues)
        val srtFile = File(activity.filesDir, "transcripts").apply { mkdirs() }
            .let { File(it, "capicut_${System.currentTimeMillis()}.srt") }
        srtFile.writeText(srt)
        viewModel.executeCommand(
            AddOperationCommand(
                EditOperation.AddSubtitles(
                    subtitlesUri = Uri.fromFile(srtFile),
                    srtContent = srt,
                    fileName = srtFile.name,
                    cues = cues
                ),
                "CapiCut subtitles"
            )
        )
        val fillers = FillerWordDetector.findFillers(transcript)
        if (fillers.isNotEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.capicut_fillers_title)
                .setMessage(activity.getString(R.string.capicut_fillers_found, fillers.size))
                .setPositiveButton(R.string.capicut_fillers_remove) { _, _ ->
                    applyFillerCuts(transcript)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            Toast.makeText(activity, R.string.capicut_transcript_ready, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFillerCuts(transcript: Transcript) {
        val source = viewModel.project.value?.sourceUri ?: return
        val duration = viewModel.project.value?.getDurationAfterTrims()
            ?: transcript.words.lastOrNull()?.endMs
            ?: 0L
        val hits = FillerWordDetector.toHits(transcript)
        val keep = FillerWordDetector.keepSegmentsAfterRemoval(duration, hits)
            .map { SpeechSegment(it.first, it.second) }
        val ops = AutoCutApplier.toOperations(source, keep)
        if (ops.isEmpty()) return
        viewModel.executeCommand(MutateListCommand("CapiCut fillers") { current ->
            current.filterNot { it is EditOperation.Trim || it is EditOperation.Merge } + ops
        })
        Toast.makeText(activity, R.string.capicut_fillers_removed, Toast.LENGTH_SHORT).show()
    }

    private fun parseWords(json: String): List<TranscriptWord> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        TranscriptWord(
                            startMs = o.optLong("startMs"),
                            endMs = o.optLong("endMs"),
                            text = o.optString("text")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun copyWavToMusic(src: File) {
        if (!src.exists()) {
            Toast.makeText(activity, R.string.capicut_wav_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val name = "CapiCut_${System.currentTimeMillis()}.wav"
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/CapiCut")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(activity, R.string.capicut_wav_failed, Toast.LENGTH_LONG).show()
                return
            }
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: run {
                    Toast.makeText(activity, R.string.capicut_wav_failed, Toast.LENGTH_LONG).show()
                    return
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Toast.makeText(activity, R.string.capicut_wav_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "WAV export failed", e)
            Toast.makeText(activity, e.message ?: activity.getString(R.string.capicut_wav_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun startAutosave() {
        autosaveJob = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(30_000)
                    persistNow()
                }
            }
        }
    }

    private fun persistNow() {
        val project = viewModel.project.value ?: return
        try {
            val recipe = EditRecipe.fromVideoProject(project.sourceName.ifBlank { "Untitled" }, project)
            val meta = repo.save(recipe, localProjectId, project.sourceName.ifBlank { "Untitled" })
            localProjectId = meta.id
        } catch (e: Exception) {
            Log.w(TAG, "Autosave failed: ${e.message}")
        }
    }

    private fun showProgress(text: String) {
        progressBanner?.visibility = View.VISIBLE
        progressBanner?.text = text
    }

    private fun hideProgress() {
        progressBanner?.visibility = View.GONE
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (!notificationsGranted()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val TAG = "CapicutEditorHooks"
    }
}
