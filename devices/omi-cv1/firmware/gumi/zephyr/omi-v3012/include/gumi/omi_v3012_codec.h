#ifndef GUMI_OMI_V3012_CODEC_H
#define GUMI_OMI_V3012_CODEC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_OMI_V3012_CODEC_SAMPLE_RATE 16000U
#define GUMI_OMI_V3012_CODEC_FRAME_SAMPLES 320U
#define GUMI_OMI_V3012_CODEC_MAX_PACKET_BYTES 256U

typedef enum {
    GUMI_OMI_V3012_CODEC_UNINITIALIZED = 0,
    GUMI_OMI_V3012_CODEC_CLOSED,
    GUMI_OMI_V3012_CODEC_OPENING,
    GUMI_OMI_V3012_CODEC_ACTIVE,
    GUMI_OMI_V3012_CODEC_CLOSING,
    GUMI_OMI_V3012_CODEC_FAULTED,
    GUMI_OMI_V3012_CODEC_UNKNOWN,
} gumi_omi_v3012_codec_truth;

typedef enum {
    /* Privacy/safety stop: suppress in-flight output and discard every queued sample. */
    GUMI_OMI_V3012_CODEC_CLOSE_DISCARD = 0,
    /* Normal finalization: emit queued complete frames and discard only an incomplete tail. */
    GUMI_OMI_V3012_CODEC_CLOSE_DRAIN_COMPLETE,
} gumi_omi_v3012_codec_close_mode;

typedef struct {
    uint64_t session_id;
    uint64_t packet_sequence;
    const uint8_t *bytes;
    size_t size;
    size_t pcm_sample_count;
} gumi_omi_v3012_codec_packet;

typedef struct {
    uint64_t session_id;
    uint64_t submitted_samples;
    uint64_t emitted_packets;
    uint64_t emitted_samples;
    uint64_t discarded_samples;
    int terminal_error;
} gumi_omi_v3012_codec_close_result;

/*
 * Packet bytes are valid only for this non-blocking callback. The callback must enqueue lifecycle
 * work and must never call close directly.
 */
typedef void (*gumi_omi_v3012_codec_packet_handler)(
    const gumi_omi_v3012_codec_packet *packet,
    void *context
);

/*
 * Called from the PCM producer or codec thread. It must enqueue lifecycle work and must never call
 * close directly; successful close joins the codec thread to establish the callback barrier.
 */
typedef void (*gumi_omi_v3012_codec_fault_handler)(
    uint64_t session_id,
    int error,
    void *context
);

/* Self-tests and configures the bundled upstream Opus encoder, but leaves the session closed. */
int gumi_omi_v3012_codec_init(
    gumi_omi_v3012_codec_packet_handler packet_handler,
    gumi_omi_v3012_codec_fault_handler fault_handler,
    void *context
);

/* Opens a fresh encoder state and empty PCM ring. session_id must be nonzero and never reused. */
int gumi_omi_v3012_codec_open(uint64_t session_id);

/*
 * Copies PCM into the bounded one-producer/one-consumer ring. A stale session token is rejected
 * without mutating the active session. Buffer exhaustion faults the session instead of dropping
 * audio silently.
 */
int gumi_omi_v3012_codec_submit_pcm(
    uint64_t session_id,
    const int16_t *samples,
    size_t sample_count
);

/*
 * Thread-context API. A successful return guarantees that no packet callback for session_id can run
 * afterward, the worker is joined, and all PCM and Opus state has been purged.
 */
int gumi_omi_v3012_codec_close(
    uint64_t session_id,
    gumi_omi_v3012_codec_close_mode mode,
    gumi_omi_v3012_codec_close_result *result
);

gumi_omi_v3012_codec_truth gumi_omi_v3012_codec_get_truth(void);

#ifdef __cplusplus
}
#endif

#endif
