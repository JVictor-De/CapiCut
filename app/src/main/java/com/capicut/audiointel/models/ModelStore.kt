package com.capicut.audiointel.models

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val bytesHint: Long,
    /** Extra files that must sit beside [fileName] (same download base / pack). */
    val packFiles: List<PackFile> = emptyList()
)

data class PackFile(
    val fileName: String,
    val url: String,
    val bytesHint: Long = 0L
)

/**
 * On-demand model files under filesDir/models. Network is used only here.
 */
class ModelStore(private val context: Context) {

    private val root = File(context.filesDir, "models").apply { mkdirs() }

    fun catalog(tier: QualityTier): List<ModelSpec> = when (tier) {
        QualityTier.HIGH -> listOf(
            whisper(
                id = "whisper-large-turbo",
                display = "Whisper large-v3-turbo INT8",
                file = "ggml-large-v3-turbo-q8_0.bin",
                bytes = 800_000_000L
            ),
            deepFilterPack(),
            sileroVad()
        )
        QualityTier.MEDIUM -> listOf(
            whisper(
                id = "whisper-medium",
                display = "Whisper medium INT8",
                file = "ggml-medium-q8_0.bin",
                bytes = 400_000_000L
            ),
            deepFilterPack(),
            sileroVad()
        )
        QualityTier.LOW -> listOf(
            whisper(
                id = "whisper-small",
                display = "Whisper small INT8",
                file = "ggml-small-q8_0.bin",
                bytes = 80_000_000L
            ),
            deepFilterPack(),
            sileroVad()
        )
    }

    fun fileFor(spec: ModelSpec): File =
        if (spec.packFiles.isNotEmpty()) File(packDir(spec), spec.fileName)
        else File(root, spec.fileName)

    fun packDir(spec: ModelSpec): File = File(root, spec.id).apply { mkdirs() }

    fun isPresent(spec: ModelSpec): Boolean {
        val primary = fileFor(spec)
        if (!primary.exists() || primary.length() <= 0L) return false
        return spec.packFiles.all { pf ->
            val f = File(packDir(spec), pf.fileName)
            f.exists() && f.length() > 0L
        }
    }

    fun remove(spec: ModelSpec) {
        if (spec.packFiles.isNotEmpty()) {
            packDir(spec).deleteRecursively()
        } else {
            fileFor(spec).delete()
        }
    }

    /**
     * Downloads [spec.url] (and pack siblings) when non-empty.
     */
    fun download(spec: ModelSpec, onProgress: (Int) -> Unit = {}): Result<File> {
        if (spec.url.isBlank()) {
            return Result.failure(IllegalStateException("Model URL not configured for ${spec.id}"))
        }
        return try {
            val destDir = if (spec.packFiles.isNotEmpty()) packDir(spec) else root
            destDir.mkdirs()
            val all = listOf(PackFile(spec.fileName, spec.url, spec.bytesHint)) + spec.packFiles
            val totalHint = all.sumOf { it.bytesHint.coerceAtLeast(1L) }
            var copiedAll = 0L
            for (pf in all) {
                if (pf.url.isBlank()) {
                    return Result.failure(IllegalStateException("Pack URL missing for ${pf.fileName}"))
                }
                downloadOne(pf.url, File(destDir, pf.fileName), pf.bytesHint) { chunk ->
                    copiedAll += chunk
                    if (totalHint > 0) {
                        onProgress(((copiedAll * 100) / totalHint).toInt().coerceIn(0, 100))
                    }
                }.getOrElse { return Result.failure(it) }
            }
            onProgress(100)
            Result.success(fileFor(spec))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadOne(
        url: String,
        dest: File,
        bytesHint: Long,
        onBytes: (Long) -> Unit
    ): Result<File> {
        val tmp = File(dest.absolutePath + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return Result.failure(IllegalStateException("HTTP ${conn.responseCode} for $url"))
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (dest.length() <= 0L && bytesHint > 0L) {
                dest.delete()
                return Result.failure(IllegalStateException("Empty download for ${dest.name}"))
            }
            Result.success(dest)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    companion object {
        private const val HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
        private const val HF_DFN = "https://huggingface.co/bitsydarel/deepfilternet3-onnx/resolve/main"
        private const val SILERO_URL =
            "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"

        private fun whisper(id: String, display: String, file: String, bytes: Long) = ModelSpec(
            id = id,
            displayName = display,
            fileName = file,
            url = "$HF_WHISPER/$file",
            bytesHint = bytes
        )

        private fun sileroVad() = ModelSpec(
            id = "silero-vad",
            displayName = "Silero VAD",
            fileName = "silero_vad.onnx",
            url = SILERO_URL,
            bytesHint = 2_000_000L
        )

        /**
         * Primary file is enc.onnx; siblings live under models/deepfilter3/.
         */
        private fun deepFilterPack() = ModelSpec(
            id = "deepfilter3",
            displayName = "DeepFilterNet3",
            fileName = "enc.onnx",
            url = "$HF_DFN/enc.onnx",
            bytesHint = 2_000_000L,
            packFiles = listOf(
                PackFile("erb_dec.onnx", "$HF_DFN/erb_dec.onnx", 3_500_000L),
                PackFile("df_dec.onnx", "$HF_DFN/df_dec.onnx", 3_500_000L),
                PackFile("config.ini", "$HF_DFN/config.ini", 2_048L)
            )
        )
    }
}
