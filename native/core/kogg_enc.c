#include "koggopus.h"

#include <stdlib.h>
#include <string.h>

#include <opusenc.h>

struct kogg_encoder {
    OggOpusEnc *ope;
    unsigned char *out;
    size_t len;
    size_t cap;
    int out_of_memory;
    int started; /* headers written */
    int finished;
};

const char *kogg_strerror(int error) {
    switch (error) {
        case KOGG_OK: return "success";
        case KOGG_ERR_ARG: return "invalid argument";
        case KOGG_ERR_ALLOC: return "out of memory";
        case KOGG_ERR_BAD_HEADER: return "malformed OpusHead header";
        case KOGG_ERR_UNSUPPORTED: return "unsupported stream (only mono and stereo are supported)";
        case KOGG_ERR_FINISHED: return "encoder already finished";
        case KOGG_ERR_TOO_LARGE: return "too much decoded audio for one call";
        default: return ope_strerror(error);
    }
}

static int on_write(void *user_data, const unsigned char *ptr, opus_int32 len) {
    kogg_encoder *enc = user_data;
    size_t needed = enc->len + (size_t) len;
    if (needed > enc->cap) {
        size_t cap = enc->cap ? enc->cap : 4096;
        while (cap < needed) cap *= 2;
        unsigned char *grown = realloc(enc->out, cap);
        if (!grown) {
            enc->out_of_memory = 1;
            return 1;
        }
        enc->out = grown;
        enc->cap = cap;
    }
    memcpy(enc->out + enc->len, ptr, (size_t) len);
    enc->len = needed;
    return 0;
}

static int on_close(void *user_data) {
    (void) user_data;
    return 0;
}

static const OpusEncCallbacks callbacks = {on_write, on_close};

static int configure(OggOpusEnc *ope, const kogg_encoder_config *c) {
    int ret;
    /* libopusenc defaults hold back 2 s of audio and 1 s of pages; this is a streaming encoder. */
    if ((ret = ope_encoder_ctl(ope, OPE_SET_DECISION_DELAY(0)))) return ret;
    if ((ret = ope_encoder_ctl(ope, OPE_SET_MUXING_DELAY(c->page_ms * 48)))) return ret;
    if ((ret = ope_encoder_ctl(ope, OPE_SET_COMMENT_PADDING(0)))) return ret;
    if ((ret = ope_encoder_ctl(ope, OPE_SET_SERIALNO(c->serial)))) return ret;
    if ((ret = ope_encoder_ctl(ope, OPUS_SET_APPLICATION(c->application)))) return ret;
    if ((ret = ope_encoder_ctl(ope, OPUS_SET_VBR(c->vbr ? 1 : 0)))) return ret;
    if (c->bitrate > 0 && (ret = ope_encoder_ctl(ope, OPUS_SET_BITRATE(c->bitrate)))) return ret;
    if (c->complexity >= 0 && (ret = ope_encoder_ctl(ope, OPUS_SET_COMPLEXITY(c->complexity)))) return ret;
    if (c->max_bandwidth > 0 && (ret = ope_encoder_ctl(ope, OPUS_SET_MAX_BANDWIDTH(c->max_bandwidth)))) return ret;
    return KOGG_OK;
}

kogg_encoder *kogg_encoder_create(const kogg_encoder_config *config, int *error) {
    int err = KOGG_OK;
    kogg_encoder *enc = NULL;
    OggOpusComments *comments = NULL;

    if (!config || config->sample_rate <= 0 || config->channels < 1 || config->channels > 2 ||
        config->page_ms < 0 || config->page_ms > 1000 || config->comment_count < 0 ||
        (config->comment_count > 0 && !config->comments)) {
        err = KOGG_ERR_ARG;
        goto fail;
    }
    enc = calloc(1, sizeof(*enc));
    comments = ope_comments_create();
    if (!enc || !comments) {
        err = KOGG_ERR_ALLOC;
        goto fail;
    }
    for (int i = 0; i < config->comment_count; i++) {
        if ((err = ope_comments_add_string(comments, config->comments[i]))) goto fail;
    }
    /* Channel mapping family 0: mono or stereo. */
    enc->ope = ope_encoder_create_callbacks(&callbacks, enc, comments, config->sample_rate,
                                            config->channels, 0, &err);
    if (!enc->ope) goto fail;
    if ((err = configure(enc->ope, config))) goto fail;

    ope_comments_destroy(comments); /* the encoder keeps its own copy */
    if (error) *error = KOGG_OK;
    return enc;

fail:
    if (comments) ope_comments_destroy(comments);
    kogg_encoder_destroy(enc);
    if (error) *error = err ? err : KOGG_ERR_ALLOC;
    return NULL;
}

static int result(kogg_encoder *enc, int ret) {
    if (enc->out_of_memory) return KOGG_ERR_ALLOC;
    return ret;
}

int kogg_encoder_headers(kogg_encoder *enc) {
    if (enc->finished) return KOGG_ERR_FINISHED;
    if (enc->started) return KOGG_OK; /* libopusenc would report OPE_TOO_LATE */
    enc->started = 1;
    return result(enc, ope_encoder_flush_header(enc->ope));
}

int kogg_encoder_write(kogg_encoder *enc, const int16_t *pcm, int frames) {
    if (enc->finished) return KOGG_ERR_FINISHED;
    if (frames < 0 || (frames > 0 && !pcm)) return KOGG_ERR_ARG;
    if (frames == 0) return KOGG_OK;
    enc->started = 1;
    return result(enc, ope_encoder_write(enc->ope, pcm, frames));
}

int kogg_encoder_finish(kogg_encoder *enc) {
    if (enc->finished) return KOGG_ERR_FINISHED;
    enc->finished = 1;
    enc->started = 1;
    return result(enc, ope_encoder_drain(enc->ope));
}

const unsigned char *kogg_encoder_take(kogg_encoder *enc, size_t *len) {
    *len = enc->len;
    enc->len = 0;
    return enc->out;
}

void kogg_encoder_destroy(kogg_encoder *enc) {
    if (!enc) return;
    if (enc->ope) ope_encoder_destroy(enc->ope);
    free(enc->out);
    free(enc);
}
