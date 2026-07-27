/*
 * v0007 capacity preflight.
 *
 * Keep the v0006 storage port byte-for-byte reproducible and interpose only
 * its directory-creation call. The mounted volume is measured first, so a full
 * legacy volume reports ENOSPC and its real free-byte count without attempting
 * any filesystem mutation.
 */
#include <zephyr/fs/fs.h>

static int gumi_v0007_capacity_checked_mkdir(const char *path);

#define fs_mkdir gumi_v0007_capacity_checked_mkdir
#include "recording_storage_port.c"
#undef fs_mkdir

static int gumi_v0007_capacity_checked_mkdir(const char *path)
{
    int error = refresh_free_bytes();

    if (error < 0) {
        return error;
    }
    return fs_mkdir(path);
}
