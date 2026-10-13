/*
 * Feeds truncated, corrupted and random input to the decoder. Meant to run under
 * AddressSanitizer and UndefinedBehaviorSanitizer: passing means no crash, no report, and
 * the same audio however the input was split up and whichever API read it.
 *
 * Built with -DKOGG_FUZZ it exposes a libFuzzer entry point instead of main().
 * Run as `kogg_hostile --seeds DIR` it writes a seed corpus for that fuzzer.
 */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ogg/ogg.h>
#include <opus_defines.h>

#include "koggopus.h"

static uint32_t rng_state = 0x9E3779B9u;

static uint32_t rng(void) {
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 17;
    rng_state ^= rng_state << 5;
    return rng_state;
}

/* What a decode produced: enough to tell two runs apart without keeping the audio. */
typedef struct {
    size_t samples;
    uint64_t hash;
} decoded;

static void add(decoded *d, const int16_t *pcm, size_t samples) {
    for (size_t i = 0; i < samples; i++) d->hash = (d->hash ^ (uint16_t) pcm[i]) * 1099511628211u; /* FNV-1a */
    d->samples += samples;
}

/* Decodes with feed() in random-sized chunks. Errors are expected and ignored. */
static decoded decode_feed(const unsigned char *data, size_t len, size_t max_chunk) {
    int error;
    kogg_decoder *dec = kogg_decoder_create(48000, 0, &error);
    assert(dec);
    decoded d = {0, 14695981039346656037u};
    size_t pos = 0;
    while (pos < len) {
        size_t chunk = max_chunk >= len ? len : 1 + rng() % max_chunk;
        if (chunk > len - pos) chunk = len - pos;
        kogg_decoder_feed(dec, data + pos, chunk);
        size_t samples;
        const int16_t *pcm = kogg_decoder_take(dec, &samples);
        add(&d, pcm, samples);
        pos += chunk;
    }
    kogg_decoder_destroy(dec);
    return d;
}

/* Decodes with push() and read() in random-sized chunks, through a buffer of random size. */
static decoded decode_read(const unsigned char *data, size_t len, size_t max_chunk) {
    int error;
    kogg_decoder *dec = kogg_decoder_create(48000, 0, &error);
    assert(dec);
    decoded d = {0, 14695981039346656037u};
    int16_t pcm[700];
    size_t capacity = 2 + rng() % (sizeof(pcm) / sizeof(pcm[0]) - 1);
    size_t pos = 0;
    while (pos < len) {
        size_t chunk = 1 + rng() % max_chunk;
        if (chunk > len - pos) chunk = len - pos;
        kogg_decoder_push(dec, data + pos, chunk);
        for (;;) {
            size_t samples;
            int status = kogg_decoder_read(dec, pcm, capacity, &samples);
            assert(samples <= capacity);
            assert(status == KOGG_OK || samples == 0);
            add(&d, pcm, samples);
            if (status == KOGG_OK && samples == 0) break;
        }
        pos += chunk;
    }
    kogg_decoder_destroy(dec);
    return d;
}

/*
 * The oracle: one input must decode to the same audio in one piece, in chunks, and through
 * the pull API. Returns the number of samples.
 */
static size_t decode_checked(const unsigned char *data, size_t len, size_t max_chunk) {
    decoded whole = decode_feed(data, len, len);
    decoded fed = decode_feed(data, len, max_chunk);
    decoded read = decode_read(data, len, max_chunk);
    if (whole.samples != fed.samples || whole.hash != fed.hash ||
        whole.samples != read.samples || whole.hash != read.hash) {
        fprintf(stderr, "decodes differ: whole %zu, chunked feed %zu, push/read %zu samples\n",
                whole.samples, fed.samples, read.samples);
        abort();
    }
    return whole.samples;
}

/* Recomputes page checksums so mutated pages get past libogg and reach the packet parsers. */
static void fix_checksums(unsigned char *data, size_t len) {
    size_t pos = 0;
    while (pos + 27 <= len) {
        if (memcmp(data + pos, "OggS", 4) != 0) {
            pos++;
            continue;
        }
        size_t header_len = 27 + data[pos + 26];
        if (pos + header_len > len) break;
        size_t body_len = 0;
        for (size_t i = 27; i < header_len; i++) body_len += data[pos + i];
        if (pos + header_len + body_len > len) break;
        ogg_page page = {data + pos, (long) header_len, data + pos + header_len, (long) body_len};
        ogg_page_checksum_set(&page);
        pos += header_len + body_len;
    }
}

#ifdef KOGG_FUZZ

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    rng_state = 0x9E3779B9u; /* the same input must always take the same path */
    decode_checked(data, size, 4096);
    /* A mutated page fails its checksum, and the fuzzer cannot forge one; do it for it. */
    unsigned char *copy = malloc(size ? size : 1);
    assert(copy);
    memcpy(copy, data, size);
    fix_checksums(copy, size);
    decode_checked(copy, size, 4096);
    free(copy);
    return 0;
}

#else

/* Encodes `frames` of noise. The caller frees the result. */
static unsigned char *encode(int sample_rate, int channels, int page_ms, int frames, size_t *len) {
    static const char *const comments[] = {"TITLE=hostile", "ARTIST=koggopus"};
    kogg_encoder_config config = {
            .sample_rate = sample_rate,
            .channels = channels,
            .application = OPUS_APPLICATION_AUDIO,
            .complexity = -1,
            .vbr = 1,
            .page_ms = page_ms,
            .serial = (int32_t) rng(),
            .comments = comments,
            .comment_count = 2,
    };
    int error;
    kogg_encoder *enc = kogg_encoder_create(&config, &error);
    assert(enc);
    int16_t *pcm = malloc(sizeof(int16_t) * (size_t) frames * (size_t) channels);
    assert(pcm);
    for (int i = 0; i < frames * channels; i++) pcm[i] = (int16_t) (rng() >> 18);
    assert(kogg_encoder_write(enc, pcm, frames) == KOGG_OK);
    assert(kogg_encoder_finish(enc) == KOGG_OK);
    free(pcm);
    const unsigned char *pages = kogg_encoder_take(enc, len);
    unsigned char *copy = malloc(*len);
    assert(copy);
    memcpy(copy, pages, *len);
    kogg_encoder_destroy(enc);
    return copy;
}

static void feed_page(kogg_decoder *dec, const ogg_page *og, size_t *total) {
    assert(kogg_decoder_feed(dec, og->header, (size_t) og->header_len) == KOGG_OK);
    assert(kogg_decoder_feed(dec, og->body, (size_t) og->body_len) == KOGG_OK);
    size_t samples;
    kogg_decoder_take(dec, &samples);
    *total += samples;
}

/*
 * Rebuilds the stream with a comment header too large to keep. The decoder must skip the
 * header and still decode all of the audio behind it.
 */
static void check_oversized_tags(const unsigned char *stream, size_t len, size_t expected) {
    long tags_len = 17L * 1024 * 1024;
    unsigned char *tags = calloc(1, (size_t) tags_len);
    assert(tags);
    memcpy(tags, "OpusTags", 8);

    int error;
    kogg_decoder *dec = kogg_decoder_create(48000, 0, &error);
    assert(dec);
    size_t total = 0;

    ogg_sync_state sync;
    ogg_stream_state in, out;
    ogg_sync_init(&sync);
    memcpy(ogg_sync_buffer(&sync, (long) len), stream, len);
    ogg_sync_wrote(&sync, (long) len);
    ogg_stream_init(&out, 77);
    int packets = 0;
    ogg_page og;
    while (ogg_sync_pageout(&sync, &og) > 0) {
        if (packets == 0) ogg_stream_init(&in, ogg_page_serialno(&og));
        ogg_stream_pagein(&in, &og);
        ogg_packet op;
        while (ogg_stream_packetout(&in, &op) > 0) {
            if (packets == 1) {
                op.packet = tags;
                op.bytes = tags_len;
            }
            ogg_stream_packetin(&out, &op);
            packets++;
        }
        /* Keep the original page boundaries, and with them the granule positions. */
        ogg_page page;
        while (ogg_stream_flush(&out, &page)) feed_page(dec, &page, &total);
    }
    assert(packets > 2);

    size_t tags_seen;
    kogg_head head;
    assert(kogg_decoder_tags(dec, &tags_seen) == NULL);
    assert(kogg_decoder_head(dec, &head) == 1);
    if (total != expected) {
        fprintf(stderr, "behind oversized tags: %zu samples, expected %zu\n", total, expected);
        abort();
    }

    ogg_stream_clear(&in);
    ogg_stream_clear(&out);
    ogg_sync_clear(&sync);
    kogg_decoder_destroy(dec);
    free(tags);
}

static void write_seed(const char *dir, const char *name, const unsigned char *data, size_t len) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE *file = fopen(path, "wb");
    if (!file || fwrite(data, 1, len, file) != len || fclose(file) != 0) {
        perror(path);
        exit(1);
    }
}

/* Small valid streams that differ in the ways that change how pages and packets are laid out. */
static void write_seeds(const char *dir) {
    size_t a_len, b_len, c_len;
    unsigned char *a = encode(16000, 1, 20, 3200, &a_len);  /* one packet per page */
    unsigned char *b = encode(48000, 2, 200, 9600, &b_len); /* several packets per page */
    unsigned char *c = encode(8000, 1, 0, 400, &c_len);     /* shorter than one packet */
    write_seed(dir, "mono_16k.opus", a, a_len);
    write_seed(dir, "stereo_48k.opus", b, b_len);
    write_seed(dir, "short_8k.opus", c, c_len);

    /* Chained, with the channel count changing between the links. */
    unsigned char *chain = malloc(a_len + b_len);
    assert(chain);
    memcpy(chain, a, a_len);
    memcpy(chain + a_len, b, b_len);
    write_seed(dir, "chained.opus", chain, a_len + b_len);
    /* A link cut short after its first page, then a whole one. */
    size_t first_page = 27 + a[26] + a[27];
    memcpy(chain, a, first_page);
    memcpy(chain + first_page, b, b_len);
    write_seed(dir, "cut_link.opus", chain, first_page + b_len);

    free(chain);
    free(a);
    free(b);
    free(c);
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--seeds") == 0) {
        write_seeds(argv[2]);
        return 0;
    }

    size_t len;
    unsigned char *stream = encode(48000, 2, 60, 48000, &len);

    /* Sanity: the untouched stream decodes to exactly one second of stereo. */
    size_t clean = decode_checked(stream, len, 4096);
    if (clean != 48000 * 2) {
        fprintf(stderr, "clean stream decoded to %zu samples, expected %d\n", clean, 48000 * 2);
        return 1;
    }
    check_oversized_tags(stream, len, clean);

    unsigned char *mutated = malloc(len * 2);
    assert(mutated);
    for (int round = 0; round < 4000; round++) {
        size_t mutated_len = len;
        memcpy(mutated, stream, len);
        switch (rng() % 4) {
            case 0: /* flip a few bits */
                for (int i = 0, n = 1 + rng() % 8; i < n; i++) mutated[rng() % len] ^= (unsigned char) (1u << (rng() % 8));
                break;
            case 1: /* truncate */
                mutated_len = rng() % len;
                break;
            case 2: /* overwrite a run with noise */
                for (size_t at = rng() % len, n = rng() % 64; n > 0 && at < len; n--, at++) mutated[at] = (unsigned char) rng();
                break;
            default: /* append the stream to a truncated copy of itself (broken chain) */
                mutated_len = rng() % len;
                memcpy(mutated + mutated_len, stream, len);
                mutated_len += len;
                break;
        }
        if (round % 2) fix_checksums(mutated, mutated_len);
        decode_checked(mutated, mutated_len, 1 + rng() % 2048);
    }

    /* Pure noise, with capture patterns sprinkled in so page parsing is reached. */
    size_t noise_len = 1 << 20;
    unsigned char *noise = malloc(noise_len);
    assert(noise);
    for (size_t i = 0; i < noise_len; i++) noise[i] = (unsigned char) rng();
    for (size_t i = 0; i + 4 < noise_len; i += 997) memcpy(noise + i, "OggS", 4);
    decode_checked(noise, noise_len, 4096);

    free(noise);
    free(mutated);
    free(stream);
    puts("hostile input test passed");
    return 0;
}

#endif
