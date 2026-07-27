#include "gumi/omi_v3012_legacy_storage_reclaimer.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt_callbacks.h>
#include <zephyr/mgmt/mcumgr/grp/os_mgmt/os_mgmt_callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/mgmt_defines.h>
#include <zephyr/spinlock.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>

/* Legacy storage reclaimer service: 47554d49-0011-4f4d-492d-435631000001. */
static struct bt_uuid_128 reclaimer_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x47554D49,
        0x0011,
        0x4F4D,
        0x492D,
        0x435631000001
    ));

/* Read-only status: 47554d49-0011-4f4d-492d-435631000002. */
static struct bt_uuid_128 reclaimer_status_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x47554D49,
        0x0011,
        0x4F4D,
        0x492D,
        0x435631000002
    ));

static struct k_spinlock status_lock;
static atomic_t mgmt_guard_started = ATOMIC_INIT(0);
static atomic_t mutation_admitted = ATOMIC_INIT(0);
static uint8_t status_snapshot[
    GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_WIRE_SIZE
] = {
    GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_SCHEMA,
    GUMI_OMI_V3012_LEGACY_RECLAIMER_COLD,
};

static void put_u32_le(uint8_t *output, uint32_t value)
{
    output[0] = (uint8_t)(value & UINT32_C(0xff));
    output[1] = (uint8_t)((value >> 8U) & UINT32_C(0xff));
    output[2] = (uint8_t)((value >> 16U) & UINT32_C(0xff));
    output[3] = (uint8_t)((value >> 24U) & UINT32_C(0xff));
}

static void put_u64_le(uint8_t *output, uint64_t value)
{
    unsigned int index;

    for (index = 0U; index < 8U; index += 1U) {
        output[index] =
            (uint8_t)((value >> (index * 8U)) & UINT64_C(0xff));
    }
}

static enum mgmt_cb_return deny_mutation_until_terminal(
    uint32_t event,
    enum mgmt_cb_return previous_status,
    int32_t *return_code,
    uint16_t *return_group,
    bool *abort_more,
    void *data,
    size_t data_size
)
{
    bool valid_event_data =
        (event == MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK &&
         data != NULL &&
         data_size == sizeof(struct img_mgmt_upload_check)) ||
        (event == MGMT_EVT_OP_OS_MGMT_RESET &&
         data != NULL &&
         data_size == sizeof(struct os_mgmt_reset_data));

    ARG_UNUSED(return_group);
    if (previous_status != MGMT_CB_OK) {
        return previous_status;
    }
    if (return_code == NULL || abort_more == NULL || !valid_event_data) {
        if (return_code != NULL) {
            *return_code = MGMT_ERR_EINVAL;
        }
        if (abort_more != NULL) {
            *abort_more = true;
        }
        return MGMT_CB_ERROR_RC;
    }
    if (atomic_get(&mutation_admitted) == 0) {
        *return_code = MGMT_ERR_EACCESSDENIED;
        *abort_more = true;
        return MGMT_CB_ERROR_RC;
    }
    return MGMT_CB_OK;
}

static struct mgmt_callback image_mutation_guard = {
    .callback = deny_mutation_until_terminal,
    .event_id = MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK,
};

static struct mgmt_callback reset_mutation_guard = {
    .callback = deny_mutation_until_terminal,
    .event_id = MGMT_EVT_OP_OS_MGMT_RESET,
};

static ssize_t read_reclaimer_status(
    struct bt_conn *connection,
    const struct bt_gatt_attr *attribute,
    void *buffer,
    uint16_t length,
    uint16_t offset
)
{
    uint8_t snapshot[
        GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_WIRE_SIZE
    ];
    k_spinlock_key_t key = k_spin_lock(&status_lock);

    memcpy(snapshot, status_snapshot, sizeof(snapshot));
    k_spin_unlock(&status_lock, key);
    return bt_gatt_attr_read(
        connection,
        attribute,
        buffer,
        length,
        offset,
        snapshot,
        sizeof(snapshot)
    );
}

static void status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
);

BT_GATT_SERVICE_DEFINE(
    legacy_reclaimer_service,
    BT_GATT_PRIMARY_SERVICE(&reclaimer_service_uuid),
    BT_GATT_CHARACTERISTIC(
        &reclaimer_status_uuid.uuid,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ,
        read_reclaimer_status,
        NULL,
        NULL
    ),
    BT_GATT_CCC(status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE)
);

static void notify_status(void)
{
    uint8_t snapshot[
        GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_WIRE_SIZE
    ];
    k_spinlock_key_t key = k_spin_lock(&status_lock);

    memcpy(snapshot, status_snapshot, sizeof(snapshot));
    k_spin_unlock(&status_lock, key);
    (void)bt_gatt_notify(
        NULL,
        &legacy_reclaimer_service.attrs[1],
        snapshot,
        sizeof(snapshot)
    );
}

static void status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
)
{
    ARG_UNUSED(attribute);
    if (value == BT_GATT_CCC_NOTIFY) {
        notify_status();
    }
}

int gumi_omi_v3012_legacy_reclaimer_status_publish(
    const gumi_omi_v3012_legacy_reclaimer_status *status
)
{
    uint8_t encoded[
        GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_WIRE_SIZE
    ];
    k_spinlock_key_t key;

    if (status == NULL ||
        status->phase < GUMI_OMI_V3012_LEGACY_RECLAIMER_COLD ||
        status->phase > GUMI_OMI_V3012_LEGACY_RECLAIMER_FAILED) {
        return -EINVAL;
    }
    memset(encoded, 0, sizeof(encoded));
    encoded[0] = GUMI_OMI_V3012_LEGACY_RECLAIMER_STATUS_SCHEMA;
    encoded[1] = (uint8_t)status->phase;
    encoded[2] = (uint8_t)(status->flags & UINT16_C(0xff));
    encoded[3] = (uint8_t)((status->flags >> 8U) & UINT16_C(0xff));
    put_u32_le(&encoded[4], (uint32_t)status->last_error);
    put_u32_le(&encoded[8], status->generation);
    put_u64_le(&encoded[12], status->target_size_bytes);
    put_u64_le(&encoded[20], status->free_bytes_before);
    put_u64_le(&encoded[28], status->free_bytes_after);

    key = k_spin_lock(&status_lock);
    memcpy(status_snapshot, encoded, sizeof(status_snapshot));
    k_spin_unlock(&status_lock, key);
    notify_status();
    return 0;
}

void gumi_omi_v3012_legacy_reclaimer_mgmt_guard_start(void)
{
    if (!atomic_cas(&mgmt_guard_started, 0, 1)) {
        return;
    }
    atomic_set(&mutation_admitted, 0);
    mgmt_callback_register(&image_mutation_guard);
    mgmt_callback_register(&reset_mutation_guard);
}

void gumi_omi_v3012_legacy_reclaimer_mutation_admission_set(bool admitted)
{
    atomic_set(&mutation_admitted, admitted ? 1 : 0);
}
