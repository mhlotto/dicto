#include <jni.h>
#include <string>
#include <vector>

#if DICTO_HAS_WHISPER_CPP
#include "whisper.h"
#endif

extern "C" JNIEXPORT jlong JNICALL
Java_com_mhlotto_dicto_whisper_WhisperNative_nativeInitialize(
        JNIEnv *env,
        jobject,
        jstring model_path) {
#if DICTO_HAS_WHISPER_CPP
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
#else
    return 0;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mhlotto_dicto_whisper_WhisperNative_nativeIsWhisperBuilt(
        JNIEnv *,
        jobject) {
#if DICTO_HAS_WHISPER_CPP
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mhlotto_dicto_whisper_WhisperNative_nativeTranscribePcm(
        JNIEnv *env,
        jobject,
        jlong handle,
        jfloatArray samples) {
#if DICTO_HAS_WHISPER_CPP
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize sample_count = env->GetArrayLength(samples);
    std::vector<float> pcm(sample_count);
    env->GetFloatArrayRegion(samples, 0, sample_count, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.n_threads = 2;
    params.no_context = true;
    params.single_segment = false;

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        return env->NewStringUTF("");
    }

    std::string text;
    const int segment_count = whisper_full_n_segments(ctx);
    for (int i = 0; i < segment_count; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
        if (i + 1 < segment_count) {
            text += " ";
        }
    }
    return env->NewStringUTF(text.c_str());
#else
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_mhlotto_dicto_whisper_WhisperNative_nativeRelease(
        JNIEnv *,
        jobject,
        jlong handle) {
#if DICTO_HAS_WHISPER_CPP
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
#endif
}
