/*
 * koggopus core: streaming Ogg Opus encoding and decoding in plain C.
 *
 * The encoder wraps libopusenc. The decoder uses libogg's push parser and libopus.
 * Neither object is thread-safe.
 */
#ifndef KOGGOPUS_H
#define KOGGOPUS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Error codes. Other negative values come straight from libopusenc (see kogg_strerror). */
#define KOGG_OK 0
#define KOGG_ERR_ARG (-100)
#define KOGG_ERR_ALLOC (-101)
#define KOGG_ERR_BAD_HEADER (-102)  /* malformed OpusHead */
#define KOGG_ERR_UNSUPPORTED (-103) /* valid stream this library cannot decode */
#define KOGG_ERR_FINISHED (-104)    /* encoder used after finish */
#define KOGG_ERR_TOO_LARGE (-105)   /* one kogg_decoder_feed decoded more audio than it may buffer */

const char *kogg_strerror(int error);

/* ---- Encoder ---------------------------------------------------------------------------- */

typedef struct kogg_encoder kogg_encoder;

typedef struct {
    int sample_rate;   /* input rate in Hz; anything other than 48000 is resampled */
    int channels;      /* 1 or 2 */
    int bitrate;       /* bits per second, 0 = libopus default */
    int application;   /* OPUS_APPLICATION_* */
    int complexity;    /* 0..10, -1 = libopus default */
    int vbr;           /* 1 = VBR, 0 = CBR */
    int max_bandwidth; /* OPUS_BANDWIDTH_*, 0 = libopus default */
    int page_ms;       /* maximum audio held back before a page is emitted */
    int32_t serial;    /* Ogg bitstream serial number */
    const char *const *comments; /* "TAG=value" strings, UTF-8 */
    int comment_count;
} kogg_encoder_config;

kogg_encoder *kogg_encoder_create(const kogg_encoder_config *config, int *error);

/*
 * Each call appends any completed Ogg pages to the encoder's output buffer.
 * kogg_encoder_headers does nothing once the headers have been written.
 */
int kogg_encoder_headers(kogg_encoder *enc);
int kogg_encoder_write(kogg_encoder *enc, const int16_t *pcm, int frames);
int kogg_encoder_finish(kogg_encoder *enc);

/* Returns the buffered pages and empties the buffer. Valid until the next encoder call. */
const unsigned char *kogg_encoder_take(kogg_encoder *enc, size_t *len);

void kogg_encoder_destroy(kogg_encoder *enc);

/* ---- Decoder ---------------------------------------------------------------------------- */

typedef struct kogg_decoder kogg_decoder;

typedef struct {
    int channels;               /* of the stream; the output may differ, see kogg_decoder_channels */
    int pre_skip;               /* in 48 kHz samples */
    uint32_t input_sample_rate; /* informational */
    int output_gain;            /* Q7.8 dB, already applied to the decoded audio */
} kogg_head;

/*
 * sample_rate is the output rate: 8000, 12000, 16000, 24000 or 48000. channels is the
 * output layout, 1 or 2, or 0 to use that of the first stream. Either way it then stays
 * the same for every chained stream, whatever each stream's own channel count.
 */
kogg_decoder *kogg_decoder_create(int sample_rate, int channels, int *error);

/* Queues any chunk of an Ogg Opus stream. Nothing is decoded until kogg_decoder_read. */
int kogg_decoder_push(kogg_decoder *dec, const unsigned char *data, size_t len);

/*
 * Decodes queued data into pcm, which has room for `capacity` samples (not frames, at
 * least 2), and sets *samples to the number written, always whole frames. Zero with
 * KOGG_OK means everything queued has been decoded. An error is returned only by a call
 * that wrote nothing; the decoder stays usable, so keep reading.
 */
int kogg_decoder_read(kogg_decoder *dec, int16_t *pcm, size_t capacity, size_t *samples);

/*
 * kogg_decoder_push followed by reading everything into an internal buffer, collected with
 * kogg_decoder_take. On error the decoder stays usable and already decoded audio is kept,
 * except for KOGG_ERR_TOO_LARGE, which discards the audio buffered so far.
 */
int kogg_decoder_feed(kogg_decoder *dec, const unsigned char *data, size_t len);

/* Returns interleaved samples (not frames) and empties the buffer. Valid until the next feed. */
const int16_t *kogg_decoder_take(kogg_decoder *dec, size_t *samples);

/* Output channels, or 0 while they still depend on a stream that has not arrived. */
int kogg_decoder_channels(const kogg_decoder *dec);

/* Header of the current logical stream. Returns 1 when available, 0 before OpusHead arrived. */
int kogg_decoder_head(const kogg_decoder *dec, kogg_head *head);

/* Raw OpusTags packet of the current logical stream, or NULL. */
const unsigned char *kogg_decoder_tags(const kogg_decoder *dec, size_t *len);

/* Number of logical Opus streams started so far (more than 1 for chained streams). */
int kogg_decoder_stream_count(const kogg_decoder *dec);

void kogg_decoder_destroy(kogg_decoder *dec);

#ifdef __cplusplus
}
#endif

#endif
