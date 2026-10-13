#include "koggopus.h"

#include <stdlib.h>
#include <string.h>

#include <ogg/ogg.h>
#include <opus.h>

/* Longest audio a single Opus packet can hold. */
#define MAX_PACKET_MS 120
/* RFC 7845 section 3: larger audio packets are to be treated as invalid. */
#define MAX_AUDIO_PACKET_BYTES 61440L
/* A header packet still being reassembled past this size is skipped. */
#define MAX_HEADER_PACKET_BYTES (16L * 1024 * 1024)
/* Granule positions beyond this are ignored, which keeps the arithmetic on them from overflowing. */
#define MAX_GRANULE ((ogg_int64_t) 1 << 62)
/* Most samples one feed may produce: fits a Java array and, in bytes, a 32-bit size_t. */
#define MAX_OUT_SAMPLES ((size_t) 1 << 30)
/* An output buffer that grew past this is released once it has been taken. */
#define KEEP_OUT_SAMPLES ((size_t) 1 << 20)
/* A page has at most 255 segments, so it completes at most 255 packets. */
#define MAX_PAGE_PACKETS 255

enum state {
    ST_NONE, /* no logical stream selected */
    ST_HEAD, /* stream selected, waiting for OpusHead */
    ST_TAGS, /* waiting for OpusTags */
    ST_AUDIO
};

struct kogg_decoder {
    int rate;
    int factor;   /* 48000 / rate: granule units per output frame */
    int channels; /* output channels; 0 until the first stream settles it */

    ogg_sync_state sync;
    ogg_stream_state stream;
    int stream_ready;
    enum state state;
    int stream_count;

    OpusDecoder *opus;
    kogg_head head;
    int has_head;
    unsigned char *tags;
    size_t tags_len;

    /*
     * The audio packets of the page being decoded. They point into libogg's buffer, which
     * stays where it is until the next page is submitted.
     */
    ogg_packet packets[MAX_PAGE_PACKETS];
    int packet_count;
    int packet_next;

    /* Granule positions: samples at 48 kHz counted from the start of the stream, pre-skip included. */
    ogg_int64_t start; /* where this stream begins; -1 until its first audio page */
    ogg_int64_t pos;   /* where the next packet begins */
    ogg_int64_t end;   /* where the audio ends, known on the final page; otherwise -1 */

    int16_t *scratch;   /* one decoded packet */
    size_t pending_pos; /* samples of it not yet handed out */
    size_t pending_len;
    int deferred; /* error to report once the audio decoded before it has been read */

    int16_t *out; /* used by kogg_decoder_feed */
    size_t out_len;
    size_t out_cap;
};

kogg_decoder *kogg_decoder_create(int sample_rate, int channels, int *error) {
    if ((sample_rate != 8000 && sample_rate != 12000 && sample_rate != 16000 &&
         sample_rate != 24000 && sample_rate != 48000) || channels < 0 || channels > 2) {
        if (error) *error = KOGG_ERR_ARG;
        return NULL;
    }
    kogg_decoder *dec = calloc(1, sizeof(*dec));
    if (!dec) {
        if (error) *error = KOGG_ERR_ALLOC;
        return NULL;
    }
    dec->rate = sample_rate;
    dec->factor = 48000 / sample_rate;
    dec->channels = channels;
    ogg_sync_init(&dec->sync);
    if (error) *error = KOGG_OK;
    return dec;
}

/* Forgets the current stream. Audio already decoded from it can still be read. */
static void drop_stream(kogg_decoder *dec) {
    if (dec->stream_ready) ogg_stream_clear(&dec->stream);
    dec->stream_ready = 0;
    dec->state = ST_NONE;
    dec->packet_count = 0;
    dec->packet_next = 0;
    free(dec->tags);
    dec->tags = NULL;
    dec->tags_len = 0;
    dec->has_head = 0;
}

static int start_stream(kogg_decoder *dec, int serial) {
    drop_stream(dec);
    if (ogg_stream_init(&dec->stream, serial)) return KOGG_ERR_ALLOC;
    dec->stream_ready = 1;
    dec->state = ST_HEAD;
    return KOGG_OK;
}

/* RFC 7845 section 5.1. */
static int on_head(kogg_decoder *dec, const ogg_packet *op) {
    const unsigned char *p = op->packet;
    if (op->bytes < 8 || memcmp(p, "OpusHead", 8) != 0) {
        /* Some other codec multiplexed into the container: not ours, not an error. */
        drop_stream(dec);
        return KOGG_OK;
    }
    if (op->bytes < 19 || (p[8] & 0xF0) != 0) {
        drop_stream(dec);
        return KOGG_ERR_BAD_HEADER;
    }
    int channels = p[9];
    int family = p[18];
    if (family != 0 || channels < 1 || channels > 2) {
        drop_stream(dec);
        return channels < 1 ? KOGG_ERR_BAD_HEADER : KOGG_ERR_UNSUPPORTED;
    }
    dec->head.channels = channels;
    dec->head.pre_skip = p[10] | (p[11] << 8);
    dec->head.input_sample_rate =
            p[12] | ((uint32_t) p[13] << 8) | ((uint32_t) p[14] << 16) | ((uint32_t) p[15] << 24);
    dec->head.output_gain = (int16_t) (p[16] | (p[17] << 8));

    /*
     * The output layout is settled once and kept for every later stream: libopus converts
     * between mono and stereo, so chained streams never change the shape of the output.
     */
    if (!dec->channels) dec->channels = channels;
    if (!dec->scratch) {
        dec->scratch = malloc(sizeof(int16_t) * (size_t) (dec->rate / 1000 * MAX_PACKET_MS) * (size_t) dec->channels);
    }
    if (dec->opus) {
        opus_decoder_ctl(dec->opus, OPUS_RESET_STATE);
    } else {
        int err;
        dec->opus = opus_decoder_create(dec->rate, dec->channels, &err);
    }
    if (!dec->opus || !dec->scratch) {
        drop_stream(dec);
        return KOGG_ERR_ALLOC;
    }
    opus_decoder_ctl(dec->opus, OPUS_SET_GAIN(dec->head.output_gain));

    dec->has_head = 1;
    dec->start = -1;
    dec->pos = -1;
    dec->end = -1;
    dec->stream_count++;
    dec->state = ST_TAGS;
    return KOGG_OK;
}

static int on_tags(kogg_decoder *dec, const ogg_packet *op) {
    dec->state = ST_AUDIO;
    if (op->bytes < 8 || memcmp(op->packet, "OpusTags", 8) != 0) return KOGG_OK;
    dec->tags = malloc((size_t) op->bytes);
    if (!dec->tags) return KOGG_ERR_ALLOC;
    memcpy(dec->tags, op->packet, (size_t) op->bytes);
    dec->tags_len = (size_t) op->bytes;
    return KOGG_OK;
}

/* Length of an audio packet in 48 kHz samples, read from its first bytes; 0 if it is not valid. */
static int packet_duration(const ogg_packet *op) {
    /* A zero-length packet would make libopus synthesise concealment audio. */
    if (op->bytes <= 0 || op->bytes > MAX_AUDIO_PACKET_BYTES) return 0;
    int samples = opus_packet_get_nb_samples(op->packet, (opus_int32) op->bytes, 48000);
    return samples > 0 && samples <= 48 * MAX_PACKET_MS ? samples : 0;
}

/*
 * Works out where the packets just collected from a page sit in the stream. `total` is
 * their combined duration. See RFC 7845 section 4.
 */
static void place_page(kogg_decoder *dec, const ogg_page *og, ogg_int64_t total) {
    ogg_int64_t granule = ogg_page_granulepos(og);
    if (granule > MAX_GRANULE) granule = -1;
    int eos = ogg_page_eos(og);
    if (granule < 0) {
        /* Not allowed on a page that completes packets; carry on from where we were. */
        if (dec->pos < 0) dec->pos = 0;
    } else if (!eos || dec->pos < 0) {
        /*
         * A page's granule position is where its last packet ends, so the first one begins
         * `total` earlier. Taking this from every page, rather than counting, keeps the
         * position right after lost pages and for streams that do not begin at zero. On
         * the final page the position may be short of the audio (that is how the end is
         * trimmed), so there we keep counting, or begin at zero if there was no page before.
         */
        dec->pos = granule > total ? granule - total : 0;
    }
    if (dec->start < 0) dec->start = dec->pos;
    dec->end = eos && granule >= 0 ? granule : -1;
}

/* Decodes the next collected packet into scratch and marks the part of it to hand out. */
static void decode_next(kogg_decoder *dec) {
    const ogg_packet *op = &dec->packets[dec->packet_next++];
    ogg_int64_t begin = dec->pos;
    dec->pos += packet_duration(op);
    int frames = opus_decode(dec->opus, op->packet, (opus_int32) op->bytes, dec->scratch,
                             dec->rate / 1000 * MAX_PACKET_MS, 0);
    /* Corrupt packets are skipped; there is no loss concealment. */
    if (frames <= 0) return;

    /* In output frames: the pre-skip, and this packet's index within the stream. */
    ogg_int64_t pre_skip = dec->head.pre_skip / dec->factor;
    ogg_int64_t index = begin > dec->start ? (begin - dec->start) / dec->factor : 0;
    ogg_int64_t skip = pre_skip > index ? pre_skip - index : 0;
    if (skip > frames) skip = frames;
    ogg_int64_t keep = frames - skip;

    /* The final granule position says where the audio really ends (RFC 7845 section 4.4). */
    if (dec->end >= 0) {
        ogg_int64_t length = dec->end - dec->start - dec->head.pre_skip;
        ogg_int64_t limit = length > 0 ? length / dec->factor : 0;
        ogg_int64_t before = index + skip - pre_skip; /* audio frames ahead of the part kept */
        if (before + keep > limit) keep = limit > before ? limit - before : 0;
    }
    dec->pending_pos = (size_t) skip * (size_t) dec->channels;
    dec->pending_len = (size_t) keep * (size_t) dec->channels;
}

static int on_page(kogg_decoder *dec, ogg_page *og) {
    int serial = ogg_page_serialno(og);
    if (ogg_page_bos(og)) {
        /*
         * While headers are still arriving, another BOS page belongs to a stream multiplexed
         * alongside ours and is ignored, unless it is Opus: then our stream was cut short
         * after its first page and this is the next one. Once audio has started, any BOS
         * page begins a new chained stream.
         */
        int opus = og->body_len >= 8 && memcmp(og->body, "OpusHead", 8) == 0;
        if (dec->state != ST_TAGS || opus) {
            int err = start_stream(dec, serial);
            if (err) return err;
        }
    }
    if (dec->state == ST_NONE || serial != dec->stream.serialno) return KOGG_OK;

    /*
     * A packet that is still incomplete and already too large is hostile or broken. Drop
     * what we hold of it; libogg then discards the rest as it arrives, and the stream
     * carries on, without its tags if that is what the packet was.
     */
    long pending = dec->stream.body_fill - dec->stream.body_returned;
    if (pending > (dec->state == ST_AUDIO ? MAX_AUDIO_PACKET_BYTES : MAX_HEADER_PACKET_BYTES)) {
        ogg_stream_reset(&dec->stream);
        if (dec->state == ST_TAGS) dec->state = ST_AUDIO;
    }
    if (ogg_stream_pagein(&dec->stream, og)) return KOGG_OK;

    int first_error = KOGG_OK;
    ogg_int64_t total = 0;
    ogg_packet op;
    int got;
    dec->packet_count = 0;
    dec->packet_next = 0;
    while (dec->state != ST_NONE && (got = ogg_stream_packetout(&dec->stream, &op)) != 0) {
        if (got < 0) continue; /* a page was lost; place_page finds the position again */
        int err = KOGG_OK;
        if (dec->state == ST_HEAD) {
            err = on_head(dec, &op);
        } else if (dec->state == ST_TAGS) {
            err = on_tags(dec, &op);
        } else {
            int duration = packet_duration(&op);
            if (duration && dec->packet_count < MAX_PAGE_PACKETS) {
                dec->packets[dec->packet_count++] = op;
                total += duration;
            }
        }
        if (err && !first_error) first_error = err;
    }
    if (dec->packet_count) place_page(dec, og, total);
    return first_error;
}

int kogg_decoder_push(kogg_decoder *dec, const unsigned char *data, size_t len) {
    if (len == 0) return KOGG_OK;
    if (!data || len > (size_t) 1 << 30) return KOGG_ERR_ARG;
    char *buffer = ogg_sync_buffer(&dec->sync, (long) len);
    if (!buffer) return KOGG_ERR_ALLOC;
    memcpy(buffer, data, len);
    if (ogg_sync_wrote(&dec->sync, (long) len)) return KOGG_ERR_ALLOC;
    return KOGG_OK;
}

int kogg_decoder_read(kogg_decoder *dec, int16_t *pcm, size_t capacity, size_t *samples) {
    *samples = 0;
    if (!pcm || capacity < 2) return KOGG_ERR_ARG;
    if (dec->deferred) {
        int err = dec->deferred;
        dec->deferred = 0;
        return err;
    }
    for (;;) {
        if (dec->pending_len) {
            size_t count = capacity - *samples;
            if (count > dec->pending_len) count = dec->pending_len;
            count -= count % (size_t) dec->channels; /* whole frames only */
            memcpy(pcm + *samples, dec->scratch + dec->pending_pos, count * sizeof(int16_t));
            dec->pending_pos += count;
            dec->pending_len -= count;
            *samples += count;
            if (dec->pending_len) return KOGG_OK; /* the caller's buffer is full */
        }
        if (dec->packet_next < dec->packet_count) {
            decode_next(dec);
            continue;
        }
        ogg_page og;
        int got = ogg_sync_pageout(&dec->sync, &og);
        if (got == 0) return KOGG_OK; /* everything pushed so far has been decoded */
        /* Negative means bytes were skipped to resynchronise (garbage or a failed checksum). */
        if (got < 0) continue;
        int err = on_page(dec, &og);
        if (err) {
            if (*samples == 0) return err;
            /* Hand out the audio first; the error is reported by the next call. */
            dec->deferred = err;
            return KOGG_OK;
        }
    }
}

int kogg_decoder_feed(kogg_decoder *dec, const unsigned char *data, size_t len) {
    if (dec->out_len == 0 && dec->out_cap > KEEP_OUT_SAMPLES) {
        free(dec->out);
        dec->out = NULL;
        dec->out_cap = 0;
    }
    int first_error = kogg_decoder_push(dec, data, len);
    if (first_error) return first_error;
    for (;;) {
        if (dec->out_cap - dec->out_len < 4096) {
            if (dec->out_len + 4096 > MAX_OUT_SAMPLES) {
                dec->out_len = 0; /* or every later feed would fail the same way */
                return KOGG_ERR_TOO_LARGE;
            }
            size_t cap = dec->out_cap ? dec->out_cap * 2 : 8192;
            int16_t *grown = realloc(dec->out, cap * sizeof(int16_t));
            if (!grown) return KOGG_ERR_ALLOC;
            dec->out = grown;
            dec->out_cap = cap;
        }
        size_t count;
        int err = kogg_decoder_read(dec, dec->out + dec->out_len, dec->out_cap - dec->out_len, &count);
        dec->out_len += count;
        if (err) {
            if (!first_error) first_error = err;
        } else if (count == 0) {
            return first_error;
        }
    }
}

const int16_t *kogg_decoder_take(kogg_decoder *dec, size_t *samples) {
    *samples = dec->out_len;
    dec->out_len = 0;
    return dec->out;
}

int kogg_decoder_channels(const kogg_decoder *dec) {
    return dec->channels;
}

int kogg_decoder_head(const kogg_decoder *dec, kogg_head *head) {
    if (!dec->has_head) return 0;
    *head = dec->head;
    return 1;
}

const unsigned char *kogg_decoder_tags(const kogg_decoder *dec, size_t *len) {
    *len = dec->tags_len;
    return dec->tags;
}

int kogg_decoder_stream_count(const kogg_decoder *dec) {
    return dec->stream_count;
}

void kogg_decoder_destroy(kogg_decoder *dec) {
    if (!dec) return;
    drop_stream(dec);
    if (dec->opus) opus_decoder_destroy(dec->opus);
    ogg_sync_clear(&dec->sync);
    free(dec->scratch);
    free(dec->out);
    free(dec);
}
