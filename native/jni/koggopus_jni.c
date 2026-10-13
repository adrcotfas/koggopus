#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "koggopus.h"

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "koggopus assumes a little-endian target"
#endif

/* The external functions of the Kotlin object io.github.adrcotfas.koggopus.Jni. */
#define JNI(name) Java_io_github_adrcotfas_koggopus_Jni_##name

static void throw_error(JNIEnv *env, int code) {
    jclass cls = (*env)->FindClass(env, "io/github/adrcotfas/koggopus/OggOpusException");
    if (!cls) return;
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    if (!ctor) return;
    jstring message = (*env)->NewStringUTF(env, kogg_strerror(code));
    if (!message) return;
    jobject exception = (*env)->NewObject(env, cls, ctor, (jint) code, message);
    if (exception) (*env)->Throw(env, (jthrowable) exception);
}

/* ---- Encoder ---------------------------------------------------------------------------- */

static void free_strings(char **strings, int count) {
    if (!strings) return;
    for (int i = 0; i < count; i++) free(strings[i]);
    free(strings);
}

/* Copies a byte[][] of UTF-8 strings into NUL-terminated C strings. */
static char **copy_strings(JNIEnv *env, jobjectArray array, int count) {
    char **strings = calloc((size_t) count + 1, sizeof(char *));
    if (!strings) return NULL;
    for (int i = 0; i < count; i++) {
        jbyteArray item = (jbyteArray) (*env)->GetObjectArrayElement(env, array, i);
        jsize len = (*env)->GetArrayLength(env, item);
        strings[i] = malloc((size_t) len + 1);
        if (!strings[i]) {
            free_strings(strings, count);
            return NULL;
        }
        (*env)->GetByteArrayRegion(env, item, 0, len, (jbyte *) strings[i]);
        strings[i][len] = '\0';
        (*env)->DeleteLocalRef(env, item);
    }
    return strings;
}

JNIEXPORT jlong JNICALL JNI(encoderCreate)(JNIEnv *env, jobject self, jint sampleRate, jint channels,
                                          jint bitrate, jint application, jint complexity,
                                          jboolean vbr, jint maxBandwidth, jint pageMs, jint serial,
                                          jobjectArray comments) {
    (void) self;
    int count = (*env)->GetArrayLength(env, comments);
    char **strings = copy_strings(env, comments, count);
    if (!strings) {
        throw_error(env, KOGG_ERR_ALLOC);
        return 0;
    }
    kogg_encoder_config config = {
            .sample_rate = sampleRate,
            .channels = channels,
            .bitrate = bitrate,
            .application = application,
            .complexity = complexity,
            .vbr = vbr,
            .max_bandwidth = maxBandwidth,
            .page_ms = pageMs,
            .serial = serial,
            .comments = (const char *const *) strings,
            .comment_count = count,
    };
    int error;
    kogg_encoder *enc = kogg_encoder_create(&config, &error);
    free_strings(strings, count);
    if (!enc) throw_error(env, error);
    return (jlong) (intptr_t) enc;
}

/* Hands the pages produced by the last call to Kotlin, or throws if that call failed. */
static jbyteArray take_pages(JNIEnv *env, kogg_encoder *enc, int status) {
    size_t len;
    const unsigned char *pages = kogg_encoder_take(enc, &len);
    if (status != KOGG_OK) {
        throw_error(env, status);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    if (result && len > 0) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) pages);
    return result;
}

JNIEXPORT jbyteArray JNICALL JNI(encoderHeaders)(JNIEnv *env, jobject self, jlong handle) {
    (void) self;
    kogg_encoder *enc = (kogg_encoder *) (intptr_t) handle;
    return take_pages(env, enc, kogg_encoder_headers(enc));
}

JNIEXPORT jbyteArray JNICALL JNI(encoderWrite)(JNIEnv *env, jobject self, jlong handle,
                                                    jshortArray pcm, jint offset, jint frames) {
    (void) self;
    kogg_encoder *enc = (kogg_encoder *) (intptr_t) handle;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!samples) return NULL;
    int status = kogg_encoder_write(enc, (const int16_t *) samples + offset, frames);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return take_pages(env, enc, status);
}

JNIEXPORT jbyteArray JNICALL JNI(encoderFinish)(JNIEnv *env, jobject self, jlong handle) {
    (void) self;
    kogg_encoder *enc = (kogg_encoder *) (intptr_t) handle;
    return take_pages(env, enc, kogg_encoder_finish(enc));
}

JNIEXPORT void JNICALL JNI(encoderDestroy)(JNIEnv *env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    kogg_encoder_destroy((kogg_encoder *) (intptr_t) handle);
}

/* ---- Decoder ---------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL JNI(decoderCreate)(JNIEnv *env, jobject self, jint sampleRate, jint channels) {
    (void) self;
    int error;
    kogg_decoder *dec = kogg_decoder_create(sampleRate, channels, &error);
    if (!dec) throw_error(env, error);
    return (jlong) (intptr_t) dec;
}

JNIEXPORT jshortArray JNICALL JNI(decoderFeed)(JNIEnv *env, jobject self, jlong handle,
                                              jbyteArray data, jint offset, jint length) {
    (void) self;
    kogg_decoder *dec = (kogg_decoder *) (intptr_t) handle;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return NULL;
    int status = kogg_decoder_feed(dec, (const unsigned char *) bytes + offset, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (status != KOGG_OK) {
        /* Audio decoded before the error stays buffered and is returned by the next feed. */
        throw_error(env, status);
        return NULL;
    }
    size_t count;
    const int16_t *samples = kogg_decoder_take(dec, &count);
    jshortArray result = (*env)->NewShortArray(env, (jsize) count);
    if (result && count > 0) (*env)->SetShortArrayRegion(env, result, 0, (jsize) count, samples);
    return result;
}

JNIEXPORT void JNICALL JNI(decoderPush)(JNIEnv *env, jobject self, jlong handle, jbyteArray data,
                                       jint offset, jint length) {
    (void) self;
    kogg_decoder *dec = (kogg_decoder *) (intptr_t) handle;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return;
    int status = kogg_decoder_push(dec, (const unsigned char *) bytes + offset, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (status != KOGG_OK) throw_error(env, status);
}

JNIEXPORT jint JNICALL JNI(decoderRead)(JNIEnv *env, jobject self, jlong handle, jshortArray pcm,
                                       jint offset, jint length) {
    (void) self;
    kogg_decoder *dec = (kogg_decoder *) (intptr_t) handle;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!samples) return 0;
    size_t count;
    int status = kogg_decoder_read(dec, samples + offset, (size_t) length, &count);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, 0);
    if (status != KOGG_OK) throw_error(env, status);
    return (jint) count;
}

JNIEXPORT jint JNICALL JNI(decoderChannels)(JNIEnv *env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    return kogg_decoder_channels((kogg_decoder *) (intptr_t) handle);
}

/* Returns {channels, preSkip, inputSampleRate, outputGain}, or null before OpusHead arrived. */
JNIEXPORT jintArray JNICALL JNI(decoderHead)(JNIEnv *env, jobject self, jlong handle) {
    (void) self;
    kogg_head head;
    if (!kogg_decoder_head((kogg_decoder *) (intptr_t) handle, &head)) return NULL;
    jint fields[4] = {head.channels, head.pre_skip, (jint) head.input_sample_rate, head.output_gain};
    jintArray result = (*env)->NewIntArray(env, 4);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 4, fields);
    return result;
}

JNIEXPORT jbyteArray JNICALL JNI(decoderTags)(JNIEnv *env, jobject self, jlong handle) {
    (void) self;
    size_t len;
    const unsigned char *tags = kogg_decoder_tags((kogg_decoder *) (intptr_t) handle, &len);
    if (!tags) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize) len);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) len, (const jbyte *) tags);
    return result;
}

JNIEXPORT jint JNICALL JNI(decoderStreamCount)(JNIEnv *env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    return kogg_decoder_stream_count((kogg_decoder *) (intptr_t) handle);
}

JNIEXPORT void JNICALL JNI(decoderDestroy)(JNIEnv *env, jobject self, jlong handle) {
    (void) env;
    (void) self;
    kogg_decoder_destroy((kogg_decoder *) (intptr_t) handle);
}
