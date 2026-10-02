#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <whisper.h>

#define LOG_TAG "capicut_whisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_capicut_audiointel_whisper_WhisperNative_transcribe(
        JNIEnv *env,
        jclass,
        jstring modelPath_,
        jfloatArray samples_) {
    if (modelPath_ == nullptr || samples_ == nullptr) {
        return nullptr;
    }
    const char *modelPath = env->GetStringUTFChars(modelPath_, nullptr);
    jsize n = env->GetArrayLength(samples_);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples_, 0, n, pcm.data());

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(modelPath_, modelPath);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed");
        return nullptr;
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.translate = false;
    wparams.language = "pt";
    wparams.n_threads = 4;
    wparams.offset_ms = 0;
    wparams.no_context = true;
    wparams.single_segment = false;

    int rc = whisper_full(ctx, wparams, pcm.data(), static_cast<int>(pcm.size()));
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        whisper_free(ctx);
        return nullptr;
    }

    std::string out;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        // whisper timestamps are in centiseconds
        out += std::to_string(t0 * 10);
        out += '\t';
        out += std::to_string(t1 * 10);
        out += '\t';
        // trim leading space
        while (*text == ' ') ++text;
        out += text;
        out += '\n';
    }
    whisper_free(ctx);
    return env->NewStringUTF(out.c_str());
}
