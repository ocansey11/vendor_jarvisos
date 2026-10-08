// needle_jni.cpp — JarvisOS bindings for the Needle engine (prebuilts/needle).
//
// Needle 3 picks tools and fills their arguments; Whistle turns speech into
// text. Both run in one process-global, non-thread-safe engine, so
// NeedleWrapper.java calls every function here from a single thread.
//
// Output: /system/lib64/libneedle_jarvis.so, loaded by NeedleWrapper.java.

#include <jni.h>

#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "needle.h"

namespace {

// Tool calls are short; a transcript of 30 s of speech with reasoning is not.
constexpr int kOutCapacity = 1 << 16;

std::string g_error;

std::string toString(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

const char* orNull(const std::string& s, jstring source) {
    return source == nullptr ? nullptr : s.c_str();
}

void rememberEngineError() {
    const char* e = needle_last_error();
    g_error = e ? e : "unknown engine error";
}

}  // namespace

extern "C" {

// Maps a .cact file and hands it to the engine, which reads it in place: the
// mapping is deliberately never released. Returns the engine's result, or -1.
JNIEXPORT jint JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeLoad(
        JNIEnv* env, jclass, jstring jpath) {
    std::string path = toString(env, jpath);
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        g_error = "cannot open " + path;
        return -1;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        g_error = "cannot stat " + path;
        return -1;
    }
    void* map = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) {
        g_error = "cannot map " + path;
        return -1;
    }
    int rc = needle_load(static_cast<const unsigned char*>(map),
                         static_cast<unsigned long long>(st.st_size));
    if (rc < 0) {
        rememberEngineError();
        munmap(map, st.st_size);
    }
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeModels(JNIEnv*, jclass) {
    return needle_models();
}

// Declares the tools (and optional session facts) once; the engine keeps the
// tokenized prefix. Returns the prefix length in tokens, negative on failure.
JNIEXPORT jint JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeInit(
        JNIEnv* env, jclass, jstring jsystem, jstring jtools, jstring jindexPath) {
    std::string system = toString(env, jsystem);
    std::string tools = toString(env, jtools);
    std::string indexPath = toString(env, jindexPath);
    int rc = needle_init(orNull(system, jsystem), orNull(tools, jtools),
                         orNull(indexPath, jindexPath));
    if (rc < 0) rememberEngineError();
    return rc;
}

// Answers text, or speech when pcm is given (16 kHz mono floats in [-1, 1]).
// Returns the engine's JSON, or null on failure.
JNIEXPORT jstring JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeComplete(
        JNIEnv* env, jclass, jstring jinput, jfloatArray jpcm, jint maxNewTokens) {
    std::string input = toString(env, jinput);
    std::vector<char> out(kOutCapacity, 0);
    int rc;
    if (jpcm != nullptr) {
        jsize n = env->GetArrayLength(jpcm);
        jfloat* pcm = env->GetFloatArrayElements(jpcm, nullptr);
        rc = needle_complete(nullptr, pcm, n, maxNewTokens, out.data(), kOutCapacity);
        env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    } else {
        rc = needle_complete(input.c_str(), nullptr, 0, maxNewTokens, out.data(), kOutCapacity);
    }
    if (rc < 0) {
        rememberEngineError();
        return nullptr;
    }
    return env->NewStringUTF(out.data());
}

JNIEXPORT void JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeSetAudio(
        JNIEnv* env, jclass, jstring jlanguage, jstring jkeywords, jboolean wordTimestamps) {
    std::string language = toString(env, jlanguage);
    std::string keywords = toString(env, jkeywords);
    needle_set_audio(orNull(language, jlanguage), orNull(keywords, jkeywords),
                     wordTimestamps ? 1 : 0);
}

// Speech to text only. Returns the engine's JSON ({"text":...}), or null.
JNIEXPORT jstring JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeTranscribe(
        JNIEnv* env, jclass, jfloatArray jpcm, jstring jlanguage, jstring jkeywords) {
    if (jpcm == nullptr) {
        g_error = "no audio";
        return nullptr;
    }
    std::string language = toString(env, jlanguage);
    std::string keywords = toString(env, jkeywords);
    std::vector<char> out(kOutCapacity, 0);
    jsize n = env->GetArrayLength(jpcm);
    jfloat* pcm = env->GetFloatArrayElements(jpcm, nullptr);
    int rc = needle_transcribe(pcm, n, orNull(language, jlanguage),
                               orNull(keywords, jkeywords), 0, out.data(), kOutCapacity);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    if (rc < 0) {
        rememberEngineError();
        return nullptr;
    }
    return env->NewStringUTF(out.data());
}

// Forgets the conversation so far; the declared tools stay.
JNIEXPORT void JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeReset(JNIEnv*, jclass) {
    needle_reset();
}

JNIEXPORT jstring JNICALL
Java_com_android_server_jarvis_inference_NeedleWrapper_nativeGetLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_error.c_str());
}

}  // extern "C"
