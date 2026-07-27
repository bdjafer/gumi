#ifndef GUMI_RECORDING_STORE_H
#define GUMI_RECORDING_STORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "gumi/recording_journal.h"

#ifdef __cplusplus
extern "C" {
#endif

#define GUMI_RECORDING_STORE_NAME_TOKEN_BYTES 4U
#define GUMI_RECORDING_STORE_OBJECT_NAME_BYTES 13U

typedef enum {
    GUMI_RECORDING_STORE_STATUS_OK = 0,
    GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT,
    GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION,
    GUMI_RECORDING_STORE_STATUS_INVALID_STATE,
    GUMI_RECORDING_STORE_STATUS_NAME_COLLISION,
    GUMI_RECORDING_STORE_STATUS_NO_SPACE,
    GUMI_RECORDING_STORE_STATUS_IO_FAILURE,
    GUMI_RECORDING_STORE_STATUS_SHORT_WRITE,
    GUMI_RECORDING_STORE_STATUS_CRYPTO_FAILURE,
    GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE,
} gumi_recording_store_status;

typedef enum {
    GUMI_RECORDING_STORE_IO_OK = 0,
    GUMI_RECORDING_STORE_IO_NOT_FOUND,
    GUMI_RECORDING_STORE_IO_ALREADY_EXISTS,
    GUMI_RECORDING_STORE_IO_NO_SPACE,
    GUMI_RECORDING_STORE_IO_CORRUPT,
    GUMI_RECORDING_STORE_IO_FAILURE,
} gumi_recording_store_io_status;

typedef enum {
    GUMI_RECORDING_STORE_PHASE_EMPTY = 0,
    GUMI_RECORDING_STORE_PHASE_ACTIVE,
    GUMI_RECORDING_STORE_PHASE_COMMITTED,
    GUMI_RECORDING_STORE_PHASE_INTERRUPTED,
    GUMI_RECORDING_STORE_PHASE_FAILED,
} gumi_recording_store_phase;

/*
 * Object names are FAT 8.3 names such as 01AB23CD.PRT and 01AB23CD.GMR.
 * A platform adapter owns the fixed directory and must never reinterpret the
 * name as an arbitrary path.
 */
typedef struct {
    gumi_recording_store_io_status (*exists)(
        void *context,
        const char *object_name,
        bool *exists
    );
    gumi_recording_store_io_status (*create_new)(
        void *context,
        const char *object_name
    );
    gumi_recording_store_io_status (*append)(
        void *context,
        const uint8_t *bytes,
        size_t size,
        size_t *written
    );
    gumi_recording_store_io_status (*sync_file)(void *context);
    gumi_recording_store_io_status (*sync_volume)(void *context);
    gumi_recording_store_io_status (*close)(void *context);
    gumi_recording_store_io_status (*rename_no_replace)(
        void *context,
        const char *source_name,
        const char *destination_name
    );
    int (*protect)(
        void *context,
        const gumi_recording_journal_plan *plan,
        const uint8_t *plaintext,
        size_t plaintext_size,
        uint8_t *protected_payload,
        size_t capacity,
        size_t *protected_size
    );
} gumi_recording_store_io;

typedef struct {
    gumi_recording_journal_config journal;
    uint8_t name_token[GUMI_RECORDING_STORE_NAME_TOKEN_BYTES];
    uint32_t sync_every_audio_records;
} gumi_recording_store_config;

/* Public for caller-owned static allocation and diagnostics. Mutate only through this API. */
typedef struct {
    gumi_recording_journal_writer writer;
    const gumi_recording_store_io *io;
    void *io_context;
    char partial_name[GUMI_RECORDING_STORE_OBJECT_NAME_BYTES];
    char committed_name[GUMI_RECORDING_STORE_OBJECT_NAME_BYTES];
    uint32_t sync_every_audio_records;
    uint32_t records_since_sync;
    gumi_recording_store_phase phase;
    bool file_open;
    bool initialized;
} gumi_recording_store;

/*
 * Creates a new .PRT object, writes the canonical journal header, and syncs
 * both file and volume before returning ACTIVE. Existing names are never
 * opened, truncated, removed, or replaced.
 */
gumi_recording_store_status gumi_recording_store_prepare(
    gumi_recording_store *store,
    const gumi_recording_store_config *config,
    const gumi_recording_store_io *io,
    void *io_context
);

/*
 * Protects and appends exactly one Opus packet. The journal ordinal advances
 * only after an exact append succeeds. A short write permanently fails this
 * store instance and leaves the last exact prefix recoverable in .PRT.
 */
gumi_recording_store_status gumi_recording_store_append_audio(
    gumi_recording_store *store,
    uint64_t source_sequence,
    uint32_t pcm_sample_count,
    const uint8_t *codec_payload,
    size_t codec_payload_size
);

/*
 * Appends the authenticated commit record, syncs, closes, then atomically
 * renames .PRT to a previously absent .GMR name. No destination is replaced.
 */
gumi_recording_store_status gumi_recording_store_finalize(
    gumi_recording_store *store
);

/*
 * Best-effort durable stop for an interrupted capture. It syncs and closes
 * the last exact prefix but never appends a commit record or renames .PRT.
 */
gumi_recording_store_status gumi_recording_store_interrupt(
    gumi_recording_store *store
);

#ifdef __cplusplus
}
#endif

#endif
