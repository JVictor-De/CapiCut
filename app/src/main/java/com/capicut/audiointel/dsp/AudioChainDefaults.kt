package com.capicut.audiointel.dsp

/**
 * Default parameters for the spoken-voice audio chain.
 * Tune by listening, not by theory alone.
 */
object AudioChainDefaults {
    const val WORK_SAMPLE_RATE_HZ = 48_000
    const val ASR_SAMPLE_RATE_HZ = 16_000

    const val HIGHPASS_FREQ_HZ = 80
    const val HIGHPASS_POLES = 2

    const val COMPRESSOR_RATIO = 3.0
    const val COMPRESSOR_THRESHOLD_DB = -18.0
    const val COMPRESSOR_ATTACK_MS = 10.0
    const val COMPRESSOR_RELEASE_MS = 100.0

    const val DEESSER_FREQ_HZ = 6500
    const val DEESSER_RATIO = 4.0
    const val DEESSER_THRESHOLD_DB = -24.0

    const val LOUDNORM_I = -16.0
    const val LOUDNORM_TP = -1.0
    const val LOUDNORM_LRA = 11.0

    const val VAD_PAD_MS = 200L
    const val VAD_MIN_SILENCE_MS = 400L
    const val CLIP_PEAK_THRESHOLD = 0.99f
}

enum class AudioChainStep {
    HIGHPASS,
    DENOISE,
    DEREVERB,
    COMPRESSOR,
    DEESSER,
    LOUDNORM
}

data class AudioChainConfig(
    /**
     * Default = DSP steps that run today without ONNX models.
     * DENOISE / DEREVERB stay off until ModelStore has real models.
     */
    val enabled: Set<AudioChainStep> = setOf(
        AudioChainStep.HIGHPASS,
        AudioChainStep.COMPRESSOR,
        AudioChainStep.DEESSER,
        AudioChainStep.LOUDNORM
    )
) {
    fun isOn(step: AudioChainStep): Boolean = step in enabled
}
