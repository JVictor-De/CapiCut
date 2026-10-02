package com.capicut.audiointel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.capicut.audiointel.dsp.AudioChainConfig
import com.capicut.audiointel.models.DeviceBenchmark
import com.capicut.audiointel.models.ModelStore
import com.capicut.audiointel.pipeline.AudioIntelligencePipeline
import com.capicut.audiointel.project.StorageGuard
import com.tharunbirla.librecuts.MainActivity
import com.tharunbirla.librecuts.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MediaProcessingService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null
    private val queue = ConcurrentLinkedQueue<Intent>()
    private val draining = AtomicBoolean(false)
    private var currentJob: Job? = null
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            val jobWasActive = currentJob?.isActive == true
            currentJob?.cancel()
            queue.clear()
            // If a job is running, CancellationException path emits ACTION_FAILURE once.
            if (!jobWasActive) {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: "cancel"
                val type = intent.getStringExtra(EXTRA_JOB_TYPE) ?: ""
                broadcastFailure(jobId, type, getString(R.string.capicut_cancelled))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            draining.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null) {
            cancelled = false
            queue.offer(Intent(intent))
        }
        ensureDrain()
        return START_NOT_STICKY
    }

    private fun ensureDrain() {
        if (!draining.compareAndSet(false, true)) return
        startAsForeground(getString(R.string.capicut_processing), 0)
        acquireWakeLock()
        currentJob = scope.launch {
            try {
                while (true) {
                    val next = queue.poll() ?: break
                    if (cancelled) break
                    processOne(next)
                }
            } finally {
                draining.set(false)
                // Restart if items arrived after poll returned null but before draining cleared.
                if (!cancelled && queue.isNotEmpty()) {
                    ensureDrain()
                } else {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Service.onTimeout — stopping gracefully")
        cancelled = true
        currentJob?.cancel()
        queue.clear()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(title: String, progress: Int) {
        val notification = buildNotification(title, progress)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MEDIA_PROCESSING is valid on older APIs when declared; prefer it on API 35+.
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun processOne(intent: Intent) {
        val type = intent.getStringExtra(EXTRA_JOB_TYPE) ?: return
        val source = intent.getStringExtra(EXTRA_SOURCE_URI)?.let { Uri.parse(it) } ?: run {
            broadcastFailure("?", type, getString(R.string.capicut_no_video))
            return
        }
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()
        val jobDir = File(filesDir, "jobs/$jobId").apply { mkdirs() }
        val pipeline = AudioIntelligencePipeline(this)

        if (!StorageGuard.hasRoom(this, 80L * 1024L * 1024L)) {
            broadcastFailure(jobId, type, getString(R.string.capicut_storage_full))
            return
        }

        try {
            when (type) {
                JOB_IMPROVE_AUDIO -> {
                    val result = pipeline.improveAudio(source, jobDir, AudioChainConfig()) { p ->
                        checkNotCancelled()
                        val pct = (p.step * 100) / p.total.coerceAtLeast(1)
                        updateNotification("${p.label} (${p.step}/${p.total})", pct)
                        broadcastProgress(jobId, type, p.label, pct)
                    }.getOrElse {
                        broadcastFailure(jobId, type, it.message ?: getString(R.string.capicut_audio_failed))
                        return
                    }
                    val stable = File(filesDir, "treated").apply { mkdirs() }
                    val dest = File(stable, "$jobId.wav")
                    result.wav.copyTo(dest, overwrite = true)
                    broadcastSuccess(jobId, type) {
                        putExtra(EXTRA_OUTPUT_WAV, dest.absolutePath)
                        putExtra(EXTRA_CLIPPED, result.clipped)
                    }
                }
                JOB_AUTO_CUT -> {
                    val segs = pipeline.autoCut(source, jobDir) { p ->
                        checkNotCancelled()
                        val pct = (p.step * 100) / p.total.coerceAtLeast(1)
                        updateNotification(p.label, pct)
                        broadcastProgress(jobId, type, p.label, pct)
                    }.getOrElse {
                        broadcastFailure(jobId, type, it.message ?: getString(R.string.capicut_vad_failed))
                        return
                    }
                    val arr = JSONArray()
                    segs.forEach { s ->
                        arr.put(JSONObject().put("startMs", s.startMs).put("endMs", s.endMs))
                    }
                    broadcastSuccess(jobId, type) {
                        putExtra(EXTRA_SEGMENTS_JSON, arr.toString())
                    }
                }
                JOB_TRANSCRIBE -> {
                    val models = ModelStore(this)
                    val bench = DeviceBenchmark(this)
                    val whisperSpec = models.catalog(bench.effectiveTier()).find { it.id.startsWith("whisper") }
                    val modelFile = whisperSpec?.takeIf { models.isPresent(it) }?.let { models.fileFor(it) }
                    if (modelFile == null) {
                        broadcastFailure(jobId, type, getString(R.string.capicut_whisper_missing))
                        return
                    }
                    pipeline.transcribe(source, jobDir) { p ->
                        checkNotCancelled()
                        val pct = (p.step * 100) / p.total.coerceAtLeast(1)
                        updateNotification(p.label, pct)
                        broadcastProgress(jobId, type, p.label, pct)
                    }.fold(
                        onSuccess = { t ->
                            val words = JSONArray()
                            t.words.forEach { w ->
                                words.put(
                                    JSONObject()
                                        .put("startMs", w.startMs)
                                        .put("endMs", w.endMs)
                                        .put("text", w.text)
                                )
                            }
                            File(jobDir, "transcript.json").writeText(
                                JSONObject()
                                    .put("text", t.fullText)
                                    .put("words", words)
                                    .toString()
                            )
                            broadcastSuccess(jobId, type) {
                                putExtra(EXTRA_TRANSCRIPT_TEXT, t.fullText)
                                putExtra(EXTRA_TRANSCRIPT_WORDS, words.toString())
                            }
                        },
                        onFailure = {
                            broadcastFailure(jobId, type, it.message ?: getString(R.string.capicut_whisper_missing))
                        }
                    )
                }
                JOB_EXPORT_WAV -> {
                    val wav = pipeline.exportWav(source, jobDir) { p ->
                        checkNotCancelled()
                        updateNotification(p.label, 50)
                        broadcastProgress(jobId, type, p.label, 50)
                    }.getOrElse {
                        broadcastFailure(jobId, type, it.message ?: getString(R.string.capicut_wav_failed))
                        return
                    }
                    val stable = File(filesDir, "exports").apply { mkdirs() }
                    val dest = File(stable, "export_$jobId.wav")
                    wav.copyTo(dest, overwrite = true)
                    broadcastSuccess(jobId, type) {
                        putExtra(EXTRA_OUTPUT_WAV, dest.absolutePath)
                    }
                }
                else -> broadcastFailure(jobId, type, getString(R.string.capicut_unknown_job))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            broadcastFailure(jobId, type, getString(R.string.capicut_cancelled))
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Job failed", e)
            broadcastFailure(jobId, type, e.message ?: getString(R.string.capicut_unknown_error))
        }
    }

    private fun checkNotCancelled() {
        if (cancelled) throw CancellationException("cancelled")
    }

    private fun broadcastProgress(jobId: String, type: String, label: String, progress: Int) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_PROGRESS)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_JOB_TYPE, type)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_PROGRESS, progress)
        )
    }

    private fun broadcastSuccess(jobId: String, type: String, extra: Intent.() -> Unit) {
        val i = Intent(ACTION_SUCCESS)
            .putExtra(EXTRA_JOB_ID, jobId)
            .putExtra(EXTRA_JOB_TYPE, type)
        i.extra()
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun broadcastFailure(jobId: String, type: String, error: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_FAILURE)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_JOB_TYPE, type)
                .putExtra(EXTRA_ERROR, error)
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "capicut:media").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CapiCut processing",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int): Notification {
        createChannel()
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, MediaProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else getString(R.string.capicut_processing))
            .setSmallIcon(R.drawable.ic_save_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(0, getString(R.string.cancel), cancel)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()
    }

    private fun updateNotification(title: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, progress))
    }

    override fun onDestroy() {
        cancelled = true
        currentJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaProcessingService"
        const val CHANNEL_ID = "CapicutProcessing"
        const val NOTIFICATION_ID = 2101
        const val ACTION_PROGRESS = "com.capicut.audiointel.ACTION_PROGRESS"
        const val ACTION_SUCCESS = "com.capicut.audiointel.ACTION_SUCCESS"
        const val ACTION_FAILURE = "com.capicut.audiointel.ACTION_FAILURE"
        const val ACTION_CANCEL = "com.capicut.audiointel.ACTION_CANCEL"

        const val EXTRA_JOB_TYPE = "job_type"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_LABEL = "label"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR = "error"
        const val EXTRA_OUTPUT_WAV = "output_wav"
        const val EXTRA_CLIPPED = "clipped"
        const val EXTRA_SEGMENTS_JSON = "segments_json"
        const val EXTRA_TRANSCRIPT_TEXT = "transcript_text"
        const val EXTRA_TRANSCRIPT_WORDS = "transcript_words"

        const val JOB_IMPROVE_AUDIO = "improve_audio"
        const val JOB_AUTO_CUT = "auto_cut"
        const val JOB_TRANSCRIBE = "transcribe"
        const val JOB_EXPORT_WAV = "export_wav"

        fun start(context: Context, jobType: String, source: Uri) {
            val i = Intent(context, MediaProcessingService::class.java)
                .putExtra(EXTRA_JOB_TYPE, jobType)
                .putExtra(EXTRA_SOURCE_URI, source.toString())
                .putExtra(EXTRA_JOB_ID, UUID.randomUUID().toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
