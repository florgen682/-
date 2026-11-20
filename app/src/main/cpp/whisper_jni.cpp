// Мост между Kotlin и whisper.cpp: загрузка модели и расшифровка речи с таймкодами каждого слова.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "ClipRadarWhisper"

namespace {

struct Ctx {
    whisper_context *w = nullptr;
    std::atomic<bool> abort{false};
};

void appendJsonString(std::string &out, const char *s) {
    out.push_back('"');
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(s); *p; ++p) {
        unsigned char c = *p;
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

bool abortCallback(void *data) {
    return static_cast<Ctx *>(data)->abort.load();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_clipradar_speech_WhisperNative_initContext(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *w = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    if (w == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "model load failed");
        return 0;
    }
    auto *c = new Ctx();
    c->w = w;
    return reinterpret_cast<jlong>(c);
}

extern "C" JNIEXPORT void JNICALL
Java_app_clipradar_speech_WhisperNative_freeContext(JNIEnv *, jclass, jlong ptr) {
    auto *c = reinterpret_cast<Ctx *>(ptr);
    if (c == nullptr) return;
    whisper_free(c->w);
    delete c;
}

extern "C" JNIEXPORT void JNICALL
Java_app_clipradar_speech_WhisperNative_abort(JNIEnv *, jclass, jlong ptr) {
    auto *c = reinterpret_cast<Ctx *>(ptr);
    if (c != nullptr) c->abort.store(true);
}

/**
 * Расшифровка. Возвращает JSON в UTF-8 (как байты — так безопаснее для любых языков):
 * {"rc":0,"lang":"en","words":[{"t":"hello","s":1.23,"e":1.50}, ...]}
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_clipradar_speech_WhisperNative_transcribe(JNIEnv *env, jclass, jlong ptr, jfloatArray samples,
                                                  jstring language, jint threads) {
    auto *c = reinterpret_cast<Ctx *>(ptr);
    std::string json = "{\"rc\":-100,\"lang\":\"\",\"words\":[]}";
    if (c == nullptr || c->w == nullptr) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(json.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte *>(json.data()));
        return arr;
    }
    const char *langChars = env->GetStringUTFChars(language, nullptr);
    std::string lang(langChars);
    env->ReleaseStringUTFChars(language, langChars);

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.translate = false;
    p.no_context = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    // Таймкоды для каждого слова: сегменты длиной в одно слово.
    p.token_timestamps = true;
    p.max_len = 1;
    p.split_on_word = true;
    p.language = lang.c_str();
    p.detect_language = false;
    p.suppress_blank = true;
    p.abort_callback = abortCallback;
    p.abort_callback_user_data = c;
    c->abort.store(false);

    int rc = whisper_full(c->w, p, pcm.data(), static_cast<int>(pcm.size()));

    json.clear();
    json += "{\"rc\":" + std::to_string(rc) + ",\"lang\":";
    int langId = whisper_full_lang_id(c->w);
    appendJsonString(json, langId >= 0 ? whisper_lang_str(langId) : "");
    json += ",\"words\":[";
    if (rc == 0) {
        const int segments = whisper_full_n_segments(c->w);
        bool first = true;
        for (int i = 0; i < segments; ++i) {
            const char *text = whisper_full_get_segment_text(c->w, i);
            if (text == nullptr) continue;
            std::string t(text);
            size_t b = t.find_first_not_of(' ');
            if (b == std::string::npos) continue;
            t = t.substr(b);
            const double t0 = whisper_full_get_segment_t0(c->w, i) * 0.01;
            const double t1 = whisper_full_get_segment_t1(c->w, i) * 0.01;
            if (!first) json.push_back(',');
            first = false;
            json += "{\"t\":";
            appendJsonString(json, t.c_str());
            char buf[64];
            snprintf(buf, sizeof(buf), ",\"s\":%.2f,\"e\":%.2f}", t0, t1);
            json += buf;
        }
    }
    json += "]}";
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(json.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte *>(json.data()));
    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_clipradar_speech_WhisperNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}
