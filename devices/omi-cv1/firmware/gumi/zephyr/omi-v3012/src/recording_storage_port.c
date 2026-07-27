#include "gumi/omi_v3012_recording_storage.h"

#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <ff.h>
#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/fs/fs.h>
#include <zephyr/kernel.h>
#include <zephyr/pm/device.h>
#include <zephyr/storage/disk_access.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>

#include "gumi/omi_v3012_crypto.h"
#include "recording_storage_pm_policy.h"

#define GUMI_STORAGE_DISK_NAME "SD"
#define GUMI_STORAGE_MOUNT_POINT "/SD:"
#define GUMI_STORAGE_DIRECTORY "/SD:/GUMI"
#define GUMI_STORAGE_FAT_DIRECTORY "SD:/GUMI"
#define GUMI_STORAGE_PATH_BYTES 32U
#define GUMI_STORAGE_THREAD_STACK_SIZE 6144
#define GUMI_STORAGE_THREAD_PRIORITY 5
#define GUMI_STORAGE_CONTROL_TIMEOUT_MS 15000
#define GUMI_STORAGE_MAX_RETAINED_RECORDINGS 1024U

typedef enum {
    STORAGE_COMMAND_INITIALIZE = 0,
    STORAGE_COMMAND_PREPARE,
    STORAGE_COMMAND_AUDIO,
    STORAGE_COMMAND_FINALIZE,
    STORAGE_COMMAND_INTERRUPT,
} storage_command_type;

typedef struct {
    uint64_t session_id;
    uint64_t packet_sequence;
    uint32_t pcm_sample_count;
    uint16_t packet_size;
    uint8_t packet[GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES];
} storage_audio_command;

typedef struct {
    storage_command_type type;
    union {
        struct {
            psa_key_id_t key_id;
            uint32_t expected_key_version;
            uint64_t minimum_free_bytes;
        } initialize;
        gumi_recording_store_config prepare;
        storage_audio_command audio;
        uint64_t session_id;
    } payload;
} storage_command;

typedef struct {
    struct fs_file_t file;
    gumi_omi_v3012_crypto_session crypto;
    char open_path[GUMI_STORAGE_PATH_BYTES];
    bool mounted;
    bool file_open;
} recording_io_context;

K_THREAD_STACK_DEFINE(gumi_storage_thread_stack, GUMI_STORAGE_THREAD_STACK_SIZE);
K_MSGQ_DEFINE(
    gumi_storage_commands,
    sizeof(storage_command),
    GUMI_OMI_V3012_RECORDING_STORAGE_QUEUE_CAPACITY,
    4
);
K_MUTEX_DEFINE(gumi_storage_control_lock);
K_SEM_DEFINE(gumi_storage_control_reply, 0, 1);

static struct k_thread gumi_storage_thread;
static struct k_spinlock gumi_active_session_lock;
static gumi_recording_store gumi_store;
static recording_io_context gumi_io;
static struct fs_mount_t gumi_mount;
static FATFS gumi_fat_fs;
static atomic_t gumi_storage_truth =
    ATOMIC_INIT(GUMI_OMI_V3012_RECORDING_STORAGE_UNINITIALIZED);
static atomic_t gumi_storage_accepting = ATOMIC_INIT(0);
static atomic_t gumi_storage_last_error = ATOMIC_INIT(0);
static atomic_t gumi_storage_fault_reported = ATOMIC_INIT(0);
static int gumi_control_result;
static uint64_t gumi_minimum_free_bytes;
static uint64_t gumi_free_bytes;
static uint64_t gumi_active_session_id;
static bool gumi_storage_thread_created;
static gumi_omi_v3012_recording_storage_fault_handler gumi_fault_handler;
static void *gumi_fault_context;

static const struct device *const gumi_sd_device =
    DEVICE_DT_GET(DT_NODELABEL(sdhc0));
static const struct gpio_dt_spec gumi_sd_enable =
    GPIO_DT_SPEC_GET_OR(DT_NODELABEL(sdcard_en_pin), gpios, {0});

static void set_truth(gumi_omi_v3012_recording_storage_truth truth)
{
    atomic_set(&gumi_storage_truth, (atomic_val_t)truth);
}

static uint64_t active_session_get(void)
{
    uint64_t session_id;
    k_spinlock_key_t key = k_spin_lock(&gumi_active_session_lock);

    session_id = gumi_active_session_id;
    k_spin_unlock(&gumi_active_session_lock, key);
    return session_id;
}

static void active_session_set(uint64_t session_id)
{
    k_spinlock_key_t key = k_spin_lock(&gumi_active_session_lock);

    gumi_active_session_id = session_id;
    k_spin_unlock(&gumi_active_session_lock, key);
}

static int store_error(gumi_recording_store_status status)
{
    switch (status) {
        case GUMI_RECORDING_STORE_STATUS_OK:
            return 0;
        case GUMI_RECORDING_STORE_STATUS_INVALID_ARGUMENT:
        case GUMI_RECORDING_STORE_STATUS_INVALID_CONFIGURATION:
            return -EINVAL;
        case GUMI_RECORDING_STORE_STATUS_INVALID_STATE:
            return -EPIPE;
        case GUMI_RECORDING_STORE_STATUS_NAME_COLLISION:
            return -EEXIST;
        case GUMI_RECORDING_STORE_STATUS_NO_SPACE:
            return -ENOSPC;
        case GUMI_RECORDING_STORE_STATUS_SHORT_WRITE:
            return -EIO;
        case GUMI_RECORDING_STORE_STATUS_CRYPTO_FAILURE:
            return -EKEYREJECTED;
        case GUMI_RECORDING_STORE_STATUS_JOURNAL_FAILURE:
        case GUMI_RECORDING_STORE_STATUS_IO_FAILURE:
        default:
            return -EIO;
    }
}

static void report_fault_once(int error)
{
    if (error >= 0) {
        error = -EIO;
    }
    atomic_set(&gumi_storage_accepting, 0);
    (void)atomic_cas(&gumi_storage_last_error, 0, error);
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_FAULTED);
    if (gumi_fault_handler != NULL &&
        atomic_cas(&gumi_storage_fault_reported, 0, 1)) {
        gumi_fault_handler(active_session_get(), error, gumi_fault_context);
    }
}

static gumi_recording_store_io_status map_errno(int error)
{
    if (error == 0) {
        return GUMI_RECORDING_STORE_IO_OK;
    }
    switch (-error) {
        case ENOENT:
            return GUMI_RECORDING_STORE_IO_NOT_FOUND;
        case EEXIST:
            return GUMI_RECORDING_STORE_IO_ALREADY_EXISTS;
        case ENOSPC:
            return GUMI_RECORDING_STORE_IO_NO_SPACE;
        case EILSEQ:
        case EBADMSG:
            return GUMI_RECORDING_STORE_IO_CORRUPT;
        default:
            return GUMI_RECORDING_STORE_IO_FAILURE;
    }
}

static gumi_recording_store_io_status map_fatfs(FRESULT result)
{
    switch (result) {
        case FR_OK:
            return GUMI_RECORDING_STORE_IO_OK;
        case FR_NO_FILE:
        case FR_NO_PATH:
            return GUMI_RECORDING_STORE_IO_NOT_FOUND;
        case FR_EXIST:
            return GUMI_RECORDING_STORE_IO_ALREADY_EXISTS;
        case FR_INT_ERR:
        case FR_INVALID_OBJECT:
            return GUMI_RECORDING_STORE_IO_CORRUPT;
        case FR_DENIED:
        case FR_DISK_ERR:
        case FR_NOT_READY:
        case FR_WRITE_PROTECTED:
        case FR_INVALID_DRIVE:
        case FR_NOT_ENABLED:
        case FR_NO_FILESYSTEM:
        case FR_MKFS_ABORTED:
        case FR_TIMEOUT:
        case FR_LOCKED:
        case FR_NOT_ENOUGH_CORE:
        case FR_TOO_MANY_OPEN_FILES:
        case FR_INVALID_PARAMETER:
        default:
            return GUMI_RECORDING_STORE_IO_FAILURE;
    }
}

static bool object_name_is_valid(const char *object_name)
{
    size_t index;

    if (object_name == NULL ||
        strlen(object_name) != GUMI_RECORDING_STORE_OBJECT_NAME_BYTES - 1U ||
        object_name[8] != '.') {
        return false;
    }
    for (index = 0U; index < 8U; index += 1U) {
        if (!((object_name[index] >= '0' && object_name[index] <= '9') ||
              (object_name[index] >= 'A' && object_name[index] <= 'F'))) {
            return false;
        }
    }
    return (memcmp(&object_name[9], "PRT", 3U) == 0) ||
           (memcmp(&object_name[9], "GMR", 3U) == 0);
}

static int build_path(
    const char *directory,
    const char *object_name,
    char output[GUMI_STORAGE_PATH_BYTES]
)
{
    int size;

    if (!object_name_is_valid(object_name)) {
        return -EINVAL;
    }
    size = snprintf(
        output,
        GUMI_STORAGE_PATH_BYTES,
        "%s/%s",
        directory,
        object_name
    );
    return size > 0 && (size_t)size < GUMI_STORAGE_PATH_BYTES ? 0 : -ENAMETOOLONG;
}

static gumi_recording_store_io_status recording_exists(
    void *context,
    const char *object_name,
    bool *exists
)
{
    struct fs_dirent entry;
    char path[GUMI_STORAGE_PATH_BYTES];
    int error;

    ARG_UNUSED(context);
    if (exists == NULL ||
        build_path(GUMI_STORAGE_DIRECTORY, object_name, path) != 0) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    error = fs_stat(path, &entry);
    if (error == -ENOENT) {
        *exists = false;
        return GUMI_RECORDING_STORE_IO_OK;
    }
    if (error < 0) {
        return map_errno(error);
    }
    if (entry.type != FS_DIR_ENTRY_FILE) {
        return GUMI_RECORDING_STORE_IO_CORRUPT;
    }
    *exists = true;
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status recording_create_new(
    void *context,
    const char *object_name
)
{
    recording_io_context *io = context;
    FIL reservation;
    char fat_path[GUMI_STORAGE_PATH_BYTES];
    FRESULT fat_result;
    int error;

    if (io == NULL || io->file_open ||
        build_path(GUMI_STORAGE_DIRECTORY, object_name, io->open_path) != 0 ||
        build_path(GUMI_STORAGE_FAT_DIRECTORY, object_name, fat_path) != 0) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }

    memset(&reservation, 0, sizeof(reservation));
    fat_result = f_open(&reservation, fat_path, FA_WRITE | FA_CREATE_NEW);
    if (fat_result != FR_OK) {
        return map_fatfs(fat_result);
    }
    fat_result = f_close(&reservation);
    if (fat_result != FR_OK) {
        return map_fatfs(fat_result);
    }

    fs_file_t_init(&io->file);
    error = fs_open(&io->file, io->open_path, FS_O_WRITE);
    if (error < 0) {
        return map_errno(error);
    }
    io->file_open = true;
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status recording_append(
    void *context,
    const uint8_t *bytes,
    size_t size,
    size_t *written
)
{
    recording_io_context *io = context;
    ssize_t result;

    if (io == NULL || !io->file_open || bytes == NULL || written == NULL) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    *written = 0U;
    result = fs_write(&io->file, bytes, size);
    if (result < 0) {
        return map_errno((int)result);
    }
    *written = (size_t)result;
    return GUMI_RECORDING_STORE_IO_OK;
}

static gumi_recording_store_io_status recording_sync_file(void *context)
{
    recording_io_context *io = context;

    if (io == NULL || !io->file_open) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    return map_errno(fs_sync(&io->file));
}

static gumi_recording_store_io_status recording_sync_volume(void *context)
{
    recording_io_context *io = context;

    if (io == NULL || !io->mounted) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    return map_errno(
        disk_access_ioctl(GUMI_STORAGE_DISK_NAME, DISK_IOCTL_CTRL_SYNC, NULL)
    );
}

static gumi_recording_store_io_status recording_close(void *context)
{
    recording_io_context *io = context;
    int error;

    if (io == NULL || !io->file_open) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    error = fs_close(&io->file);
    io->file_open = false;
    memset(io->open_path, 0, sizeof(io->open_path));
    return map_errno(error);
}

static gumi_recording_store_io_status recording_rename_no_replace(
    void *context,
    const char *source_name,
    const char *destination_name
)
{
    char source_path[GUMI_STORAGE_PATH_BYTES];
    char destination_path[GUMI_STORAGE_PATH_BYTES];
    FILINFO destination;
    FRESULT result;

    ARG_UNUSED(context);
    if (build_path(GUMI_STORAGE_FAT_DIRECTORY, source_name, source_path) != 0 ||
        build_path(
            GUMI_STORAGE_FAT_DIRECTORY,
            destination_name,
            destination_path
        ) != 0) {
        return GUMI_RECORDING_STORE_IO_FAILURE;
    }
    memset(&destination, 0, sizeof(destination));
    result = f_stat(destination_path, &destination);
    if (result == FR_OK) {
        return GUMI_RECORDING_STORE_IO_ALREADY_EXISTS;
    }
    if (result != FR_NO_FILE) {
        return map_fatfs(result);
    }
    return map_fatfs(f_rename(source_path, destination_path));
}

static int recording_protect(
    void *context,
    const gumi_recording_journal_plan *plan,
    const uint8_t *plaintext,
    size_t plaintext_size,
    uint8_t *protected_payload,
    size_t capacity,
    size_t *protected_size
)
{
    recording_io_context *io = context;

    if (io == NULL) {
        return -EINVAL;
    }
    return gumi_omi_v3012_crypto_protect(
        &io->crypto,
        plan,
        plaintext,
        plaintext_size,
        protected_payload,
        capacity,
        protected_size
    );
}

static const gumi_recording_store_io recording_operations = {
    .exists = recording_exists,
    .create_new = recording_create_new,
    .append = recording_append,
    .sync_file = recording_sync_file,
    .sync_volume = recording_sync_volume,
    .close = recording_close,
    .rename_no_replace = recording_rename_no_replace,
    .protect = recording_protect,
};

static uint32_t get_u32_le(const uint8_t *input)
{
    return (uint32_t)input[0] |
           ((uint32_t)input[1] << 8U) |
           ((uint32_t)input[2] << 16U) |
           ((uint32_t)input[3] << 24U);
}

static int read_exact(
    struct fs_file_t *file,
    uint8_t *output,
    size_t size
)
{
    size_t offset = 0U;

    while (offset < size) {
        ssize_t count = fs_read(file, &output[offset], size - offset);

        if (count < 0) {
            return (int)count;
        }
        if (count == 0) {
            return -ENODATA;
        }
        offset += (size_t)count;
    }
    return 0;
}

static bool object_is_committed(const char *object_name)
{
    return memcmp(&object_name[9], "GMR", 3U) == 0;
}

static int authenticate_recording(
    const struct fs_dirent *entry,
    uint32_t expected_key_version
)
{
    struct fs_file_t file;
    gumi_recording_journal_recovery recovery;
    gumi_recording_journal_record_view view;
    uint8_t header[GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES];
    uint8_t record[GUMI_RECORDING_JOURNAL_MAX_RECORD_BYTES];
    uint8_t plaintext[GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES];
    char path[GUMI_STORAGE_PATH_BYTES];
    size_t remaining;
    size_t record_size;
    size_t plaintext_size;
    uint32_t protected_size;
    bool committed_name;
    int error;
    int close_error;

    if (entry == NULL || entry->type != FS_DIR_ENTRY_FILE ||
        !object_name_is_valid(entry->name) ||
        build_path(GUMI_STORAGE_DIRECTORY, entry->name, path) != 0 ||
        entry->size < GUMI_RECORDING_JOURNAL_FILE_HEADER_BYTES) {
        return -EBADMSG;
    }
    committed_name = object_is_committed(entry->name);
    fs_file_t_init(&file);
    error = fs_open(&file, path, FS_O_READ);
    if (error < 0) {
        return error;
    }

    error = read_exact(&file, header, sizeof(header));
    if (error == 0 &&
        gumi_recording_journal_recovery_init(
            &recovery, header, sizeof(header)
        ) != GUMI_RECORDING_JOURNAL_STATUS_OK) {
        error = -EBADMSG;
    }
    if (error == 0 && recovery.config.key_id != expected_key_version) {
        error = -EKEYREJECTED;
    }

    remaining = entry->size - sizeof(header);
    while (error == 0 && remaining != 0U) {
        if (recovery.committed) {
            error = -EBADMSG;
            break;
        }
        if (remaining < GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES) {
            /*
             * An interrupted .PRT may end in unauthenticated torn bytes. They
             * remain untouched and are never counted as part of its recovered
             * prefix. A committed .GMR must be exact.
             */
            error = committed_name ? -EBADMSG : 0;
            break;
        }
        error = read_exact(
            &file,
            record,
            GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES
        );
        if (error < 0) {
            break;
        }
        protected_size = get_u32_le(&record[36]);
        if (protected_size < GUMI_RECORDING_JOURNAL_AES_GCM_TAG_BYTES ||
            protected_size >
                GUMI_RECORDING_JOURNAL_MAX_PROTECTED_PAYLOAD_BYTES) {
            error = -EBADMSG;
            break;
        }
        record_size =
            GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES + protected_size;
        if (remaining < record_size) {
            error = committed_name ? -EBADMSG : 0;
            break;
        }
        error = read_exact(
            &file,
            &record[GUMI_RECORDING_JOURNAL_RECORD_HEADER_BYTES],
            protected_size
        );
        if (error < 0) {
            break;
        }
        if (gumi_recording_journal_recovery_inspect_next(
                &recovery, record, record_size, &view
            ) != GUMI_RECORDING_JOURNAL_STATUS_OK) {
            error = -EBADMSG;
            break;
        }
        plaintext_size = 0U;
        error = gumi_omi_v3012_crypto_unprotect(
            &gumi_io.crypto,
            &view.plan,
            view.protected_payload,
            view.plan.protected_size,
            plaintext,
            sizeof(plaintext),
            &plaintext_size
        );
        if (error < 0) {
            break;
        }
        if (gumi_recording_journal_recovery_accept_next(
                &recovery,
                record,
                record_size,
                plaintext,
                plaintext_size
            ) != GUMI_RECORDING_JOURNAL_STATUS_OK) {
            error = -EBADMSG;
            break;
        }
        memset(plaintext, 0, sizeof(plaintext));
        remaining -= record_size;
    }
    memset(plaintext, 0, sizeof(plaintext));
    if (error == 0 && committed_name && !recovery.committed) {
        error = -EBADMSG;
    }
    close_error = fs_close(&file);
    return error < 0 ? error : close_error;
}

static int authenticate_retained_recordings(uint32_t expected_key_version)
{
    struct fs_dir_t directory;
    struct fs_dirent entry;
    uint32_t retained_count = 0U;
    int error;
    int close_error;

    fs_dir_t_init(&directory);
    error = fs_opendir(&directory, GUMI_STORAGE_DIRECTORY);
    if (error < 0) {
        return error;
    }
    for (;;) {
        memset(&entry, 0, sizeof(entry));
        error = fs_readdir(&directory, &entry);
        if (error < 0 || entry.name[0] == '\0') {
            break;
        }
        retained_count += 1U;
        if (retained_count > GUMI_STORAGE_MAX_RETAINED_RECORDINGS) {
            error = -E2BIG;
            break;
        }
        error = authenticate_recording(&entry, expected_key_version);
        if (error < 0) {
            break;
        }
    }
    close_error = fs_closedir(&directory);
    return error < 0 ? error : close_error;
}

static int refresh_free_bytes(void)
{
    struct fs_statvfs stats;
    uint64_t fragment_size;
    uint64_t free_fragments;
    int error;

    error = fs_statvfs(GUMI_STORAGE_MOUNT_POINT, &stats);
    if (error < 0) {
        return error;
    }
    fragment_size = (uint64_t)stats.f_frsize;
    free_fragments = (uint64_t)stats.f_bfree;
    if (fragment_size == UINT64_C(0) ||
        free_fragments > UINT64_MAX / fragment_size) {
        return -EOVERFLOW;
    }
    gumi_free_bytes = fragment_size * free_fragments;
    return gumi_free_bytes >= gumi_minimum_free_bytes ? 0 : -ENOSPC;
}

static int mount_storage(void)
{
    struct fs_dirent directory;
    int error;

    if (!device_is_ready(gumi_sd_device) || !gpio_is_ready_dt(&gumi_sd_enable)) {
        return -ENODEV;
    }
    error = gpio_pin_configure_dt(&gumi_sd_enable, GPIO_OUTPUT_ACTIVE);
    if (error < 0) {
        return error;
    }
    error = pm_device_action_run(gumi_sd_device, PM_DEVICE_ACTION_RESUME);
    error =
        gumi_omi_v3012_recording_storage_normalize_resume_result(error);
    if (error < 0) {
        return error;
    }

    memset(&gumi_mount, 0, sizeof(gumi_mount));
    memset(&gumi_fat_fs, 0, sizeof(gumi_fat_fs));
    gumi_mount.type = FS_FATFS;
    gumi_mount.fs_data = &gumi_fat_fs;
    gumi_mount.flags =
        FS_MOUNT_FLAG_NO_FORMAT | FS_MOUNT_FLAG_USE_DISK_ACCESS;
    gumi_mount.storage_dev = (void *)GUMI_STORAGE_DISK_NAME;
    gumi_mount.mnt_point = GUMI_STORAGE_MOUNT_POINT;
    error = fs_mount(&gumi_mount);
    if (error < 0) {
        return error;
    }
    gumi_io.mounted = true;

    error = fs_mkdir(GUMI_STORAGE_DIRECTORY);
    if (error == -EEXIST) {
        error = fs_stat(GUMI_STORAGE_DIRECTORY, &directory);
        if (error < 0) {
            return error;
        }
        if (directory.type != FS_DIR_ENTRY_DIR) {
            return -ENOTDIR;
        }
    } else if (error < 0) {
        return error;
    }
    return refresh_free_bytes();
}

static int initialize_storage(const storage_command *command)
{
    int error;

    memset(&gumi_io, 0, sizeof(gumi_io));
    memset(&gumi_store, 0, sizeof(gumi_store));
    gumi_minimum_free_bytes = command->payload.initialize.minimum_free_bytes;

    error = gumi_omi_v3012_crypto_init();
    if (error < 0) {
        return error;
    }
    error = gumi_omi_v3012_crypto_session_init(&gumi_io.crypto);
    if (error < 0) {
        return error;
    }
    error = gumi_omi_v3012_crypto_session_open(
        &gumi_io.crypto,
        command->payload.initialize.key_id
    );
    if (error < 0) {
        return error;
    }
    error = mount_storage();
    if (error < 0) {
        return error;
    }
    error = authenticate_retained_recordings(
        command->payload.initialize.expected_key_version
    );
    if (error < 0) {
        return error;
    }
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_READY);
    return 0;
}

static int prepare_storage(const storage_command *command)
{
    int error;

    if (gumi_omi_v3012_recording_storage_get_truth() !=
        GUMI_OMI_V3012_RECORDING_STORAGE_READY &&
        gumi_omi_v3012_recording_storage_get_truth() !=
            GUMI_OMI_V3012_RECORDING_STORAGE_COMMITTED &&
        gumi_omi_v3012_recording_storage_get_truth() !=
            GUMI_OMI_V3012_RECORDING_STORAGE_INTERRUPTED) {
        return -EBUSY;
    }
    error = refresh_free_bytes();
    if (error < 0) {
        return error;
    }
    error = store_error(gumi_recording_store_prepare(
        &gumi_store,
        &command->payload.prepare,
        &recording_operations,
        &gumi_io
    ));
    if (error < 0) {
        return error;
    }
    active_session_set(command->payload.prepare.journal.session_id);
    atomic_set(&gumi_storage_last_error, 0);
    atomic_set(&gumi_storage_fault_reported, 0);
    atomic_set(&gumi_storage_accepting, 1);
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_ACTIVE);
    return 0;
}

static void interrupt_open_store(void)
{
    if (gumi_store.file_open &&
        (gumi_store.phase == GUMI_RECORDING_STORE_PHASE_ACTIVE ||
         gumi_store.phase == GUMI_RECORDING_STORE_PHASE_FAILED)) {
        (void)gumi_recording_store_interrupt(&gumi_store);
    }
}

static void process_audio(const storage_audio_command *audio)
{
    int error;

    if (audio->session_id != active_session_get() ||
        !gumi_store.file_open ||
        gumi_store.phase != GUMI_RECORDING_STORE_PHASE_ACTIVE) {
        return;
    }
    error = store_error(gumi_recording_store_append_audio(
        &gumi_store,
        audio->packet_sequence,
        audio->pcm_sample_count,
        audio->packet,
        audio->packet_size
    ));
    if (error < 0) {
        interrupt_open_store();
        report_fault_once(error);
    }
}

static int finalize_storage(uint64_t session_id)
{
    int error;

    if (session_id != active_session_get() ||
        gumi_store.phase != GUMI_RECORDING_STORE_PHASE_ACTIVE ||
        !gumi_store.file_open) {
        return -ESTALE;
    }
    error = store_error(gumi_recording_store_finalize(&gumi_store));
    if (error < 0) {
        interrupt_open_store();
        return error;
    }
    active_session_set(UINT64_C(0));
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_COMMITTED);
    (void)refresh_free_bytes();
    return 0;
}

static int interrupt_storage(uint64_t session_id)
{
    int error = 0;

    if (session_id != active_session_get()) {
        return -ESTALE;
    }
    if (gumi_store.file_open) {
        error = store_error(gumi_recording_store_interrupt(&gumi_store));
    } else if (gumi_store.phase != GUMI_RECORDING_STORE_PHASE_INTERRUPTED) {
        return -EPIPE;
    }
    active_session_set(UINT64_C(0));
    if (error < 0) {
        return error;
    }
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_INTERRUPTED);
    (void)refresh_free_bytes();
    return 0;
}

static void storage_thread_entry(void *first, void *second, void *third)
{
    storage_command command;
    int result;

    ARG_UNUSED(first);
    ARG_UNUSED(second);
    ARG_UNUSED(third);

    for (;;) {
        k_msgq_get(&gumi_storage_commands, &command, K_FOREVER);
        switch (command.type) {
            case STORAGE_COMMAND_INITIALIZE:
                result = initialize_storage(&command);
                break;
            case STORAGE_COMMAND_PREPARE:
                result = prepare_storage(&command);
                break;
            case STORAGE_COMMAND_AUDIO:
                process_audio(&command.payload.audio);
                continue;
            case STORAGE_COMMAND_FINALIZE:
                result = finalize_storage(command.payload.session_id);
                break;
            case STORAGE_COMMAND_INTERRUPT:
                result = interrupt_storage(command.payload.session_id);
                break;
            default:
                result = -EINVAL;
                break;
        }
        gumi_control_result = result;
        /*
         * A CREATE_NEW collision is an expected, synchronous prepare result.
         * The composition layer retries with a new random filename token; it
         * must not poison the otherwise healthy storage port or emit an
         * asynchronous durability fault.
         */
        if (result < 0 &&
            !(command.type == STORAGE_COMMAND_PREPARE && result == -EEXIST)) {
            report_fault_once(result);
        }
        k_sem_give(&gumi_storage_control_reply);
    }
}

static int run_control(storage_command *command)
{
    int error;

    k_mutex_lock(&gumi_storage_control_lock, K_FOREVER);
    k_sem_reset(&gumi_storage_control_reply);
    error = k_msgq_put(&gumi_storage_commands, command, K_FOREVER);
    if (error == 0) {
        error = k_sem_take(
            &gumi_storage_control_reply,
            K_MSEC(GUMI_STORAGE_CONTROL_TIMEOUT_MS)
        );
    }
    if (error == 0) {
        error = gumi_control_result;
    } else {
        set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_UNKNOWN);
    }
    k_mutex_unlock(&gumi_storage_control_lock);
    return error;
}

int gumi_omi_v3012_recording_storage_init(
    psa_key_id_t key_id,
    uint32_t expected_key_version,
    uint64_t minimum_free_bytes,
    gumi_omi_v3012_recording_storage_fault_handler fault_handler,
    void *context
)
{
    storage_command command;
    int error;

    if (key_id == PSA_KEY_ID_NULL || expected_key_version == UINT32_C(0) ||
        minimum_free_bytes == UINT64_C(0)) {
        return -EINVAL;
    }
    if (gumi_storage_thread_created ||
        gumi_omi_v3012_recording_storage_get_truth() !=
            GUMI_OMI_V3012_RECORDING_STORAGE_UNINITIALIZED) {
        return -EALREADY;
    }

    gumi_fault_handler = fault_handler;
    gumi_fault_context = context;
    k_msgq_purge(&gumi_storage_commands);
    gumi_storage_thread_created = true;
    k_thread_create(
        &gumi_storage_thread,
        gumi_storage_thread_stack,
        K_THREAD_STACK_SIZEOF(gumi_storage_thread_stack),
        storage_thread_entry,
        NULL,
        NULL,
        NULL,
        GUMI_STORAGE_THREAD_PRIORITY,
        0,
        K_NO_WAIT
    );

    memset(&command, 0, sizeof(command));
    command.type = STORAGE_COMMAND_INITIALIZE;
    command.payload.initialize.key_id = key_id;
    command.payload.initialize.expected_key_version = expected_key_version;
    command.payload.initialize.minimum_free_bytes = minimum_free_bytes;
    error = run_control(&command);
    if (error < 0) {
        report_fault_once(error);
    }
    return error;
}

int gumi_omi_v3012_recording_storage_prepare(
    const gumi_recording_store_config *config
)
{
    storage_command command;

    if (config == NULL) {
        return -EINVAL;
    }
    memset(&command, 0, sizeof(command));
    command.type = STORAGE_COMMAND_PREPARE;
    command.payload.prepare = *config;
    return run_control(&command);
}

int gumi_omi_v3012_recording_storage_submit(
    uint64_t session_id,
    uint64_t packet_sequence,
    uint32_t pcm_sample_count,
    const uint8_t *packet,
    size_t packet_size
)
{
    storage_command command;
    int error;

    if (session_id == UINT64_C(0) || packet_sequence == UINT64_C(0) ||
        pcm_sample_count == UINT32_C(0) || packet == NULL ||
        packet_size == 0U ||
        packet_size > GUMI_RECORDING_JOURNAL_MAX_CODEC_PAYLOAD_BYTES) {
        return -EINVAL;
    }
    if (atomic_get(&gumi_storage_accepting) == 0 ||
        gumi_omi_v3012_recording_storage_get_truth() !=
            GUMI_OMI_V3012_RECORDING_STORAGE_ACTIVE ||
        session_id != active_session_get()) {
        return -EPIPE;
    }

    memset(&command, 0, sizeof(command));
    command.type = STORAGE_COMMAND_AUDIO;
    command.payload.audio.session_id = session_id;
    command.payload.audio.packet_sequence = packet_sequence;
    command.payload.audio.pcm_sample_count = pcm_sample_count;
    command.payload.audio.packet_size = (uint16_t)packet_size;
    memcpy(command.payload.audio.packet, packet, packet_size);
    error = k_msgq_put(&gumi_storage_commands, &command, K_NO_WAIT);
    if (error < 0) {
        report_fault_once(error == -ENOMSG ? -ENOSPC : error);
    }
    return error == -ENOMSG ? -ENOSPC : error;
}

int gumi_omi_v3012_recording_storage_finalize(uint64_t session_id)
{
    storage_command command;

    if (session_id == UINT64_C(0) ||
        gumi_omi_v3012_recording_storage_get_truth() !=
            GUMI_OMI_V3012_RECORDING_STORAGE_ACTIVE ||
        session_id != active_session_get()) {
        return -EINVAL;
    }
    atomic_set(&gumi_storage_accepting, 0);
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_FINALIZING);
    memset(&command, 0, sizeof(command));
    command.type = STORAGE_COMMAND_FINALIZE;
    command.payload.session_id = session_id;
    return run_control(&command);
}

int gumi_omi_v3012_recording_storage_interrupt(uint64_t session_id)
{
    storage_command command;
    gumi_omi_v3012_recording_storage_truth truth =
        gumi_omi_v3012_recording_storage_get_truth();

    if (session_id == UINT64_C(0) ||
        (truth != GUMI_OMI_V3012_RECORDING_STORAGE_ACTIVE &&
         truth != GUMI_OMI_V3012_RECORDING_STORAGE_FAULTED) ||
        session_id != active_session_get()) {
        return -EINVAL;
    }
    atomic_set(&gumi_storage_accepting, 0);
    set_truth(GUMI_OMI_V3012_RECORDING_STORAGE_FINALIZING);
    memset(&command, 0, sizeof(command));
    command.type = STORAGE_COMMAND_INTERRUPT;
    command.payload.session_id = session_id;
    return run_control(&command);
}

gumi_omi_v3012_recording_storage_truth
gumi_omi_v3012_recording_storage_get_truth(void)
{
    return (gumi_omi_v3012_recording_storage_truth)atomic_get(
        &gumi_storage_truth
    );
}

int gumi_omi_v3012_recording_storage_last_error(void)
{
    return (int)atomic_get(&gumi_storage_last_error);
}

uint64_t gumi_omi_v3012_recording_storage_free_bytes(void)
{
    return gumi_free_bytes;
}
