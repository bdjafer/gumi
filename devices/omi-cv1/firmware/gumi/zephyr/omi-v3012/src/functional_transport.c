#include "gumi/omi_v3012_functional_transport.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt_callbacks.h>
#include <zephyr/mgmt/mcumgr/grp/os_mgmt/os_mgmt_callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/mgmt_defines.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>

static struct bt_uuid_128 omi_family_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x19B10000,
        0xE8F2,
        0x537E,
        0x4F6C,
        0xD104768A1214
    ));

/* 47554d49-0001-4f4d-492d-435631000001 */
static struct bt_uuid_128 functional_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x47554D49,
        0x0001,
        0x4F4D,
        0x492D,
        0x435631000001
    ));

/* 47554d49-0002-4f4d-492d-435631000001 */
static struct bt_uuid_128 functional_status_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x47554D49,
        0x0002,
        0x4F4D,
        0x492D,
        0x435631000001
    ));

/* 47554d49-0003-4f4d-492d-435631000001 */
static struct bt_uuid_128 functional_capability_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x47554D49,
        0x0003,
        0x4F4D,
        0x492D,
        0x435631000001
    ));

static uint8_t status_snapshot[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES] = {
    GUMI_OMI_V3012_FUNCTIONAL_STATUS_VERSION,
};
static const uint8_t capability_snapshot[GUMI_OMI_V3012_FUNCTIONAL_CAPABILITY_BYTES] = {
    1U, /* descriptor version */
    1U, /* AudioInput.v1 */
    1U, /* CaptureControl.v1 */
    1U, /* ButtonGesture.v1 */
    1U, /* VisualIndicator.v1 */
    1U, /* Haptic.v1 */
    1U, /* LocalMediaStore.v1 */
    1U, /* FirmwareUpdate.v1 */
    0x03U, 0U, 0U, 0U, /* local recording + read-only state */
    0U, 0U, 0U, 0U,
};
static struct k_spinlock status_lock;
static atomic_t transport_started = ATOMIC_INIT(0);
static atomic_t physical_update_confirmation = ATOMIC_INIT(0);
static atomic_t capture_idle_for_update = ATOMIC_INIT(0);

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

int gumi_omi_v3012_functional_status_encode(
    const gumi_omi_v3012_functional_status *status,
    uint8_t output[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES]
)
{
    if (status == NULL || output == NULL) {
        return -EINVAL;
    }
    memset(output, 0, GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES);
    output[0] = GUMI_OMI_V3012_FUNCTIONAL_STATUS_VERSION;
    output[1] = status->capture_phase;
    output[2] = status->mic_truth;
    output[3] = status->storage_state;
    output[4] = status->key_truth;
    output[5] = status->storage_truth;
    output[6] = status->codec_truth;
    output[7] = status->flags;
    put_u64_le(&output[8], status->active_recording_id);
    put_u64_le(&output[16], status->free_bytes);
    put_u32_le(&output[24], (uint32_t)status->last_error);
    put_u32_le(&output[28], status->generation);
    return 0;
}

static enum mgmt_cb_return admit_physically_confirmed_application_image(
    uint32_t event,
    enum mgmt_cb_return previous_status,
    int32_t *return_code,
    uint16_t *return_group,
    bool *abort_more,
    void *data,
    size_t data_size
)
{
    const struct img_mgmt_upload_check *check = data;

    ARG_UNUSED(return_group);
    if (previous_status != MGMT_CB_OK) {
        return previous_status;
    }
    if (event != MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK || return_code == NULL ||
        abort_more == NULL || check == NULL || data_size != sizeof(*check) ||
        check->req == NULL) {
        if (return_code != NULL) {
            *return_code = MGMT_ERR_EINVAL;
        }
        if (abort_more != NULL) {
            *abort_more = true;
        }
        return MGMT_CB_ERROR_RC;
    }
    if (check->req->image != 0U ||
        atomic_get(&physical_update_confirmation) == 0 ||
        atomic_get(&capture_idle_for_update) == 0) {
        *return_code = MGMT_ERR_EACCESSDENIED;
        *abort_more = true;
        return MGMT_CB_ERROR_RC;
    }
    return MGMT_CB_OK;
}

static struct mgmt_callback image_admission_callback = {
    .callback = admit_physically_confirmed_application_image,
    .event_id = MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK,
};

static enum mgmt_cb_return admit_physically_confirmed_reset(
    uint32_t event,
    enum mgmt_cb_return previous_status,
    int32_t *return_code,
    uint16_t *return_group,
    bool *abort_more,
    void *data,
    size_t data_size
)
{
    ARG_UNUSED(return_group);
    if (previous_status != MGMT_CB_OK) {
        return previous_status;
    }
    if (event != MGMT_EVT_OP_OS_MGMT_RESET || return_code == NULL ||
        abort_more == NULL || data == NULL ||
        data_size != sizeof(struct os_mgmt_reset_data)) {
        if (return_code != NULL) {
            *return_code = MGMT_ERR_EINVAL;
        }
        if (abort_more != NULL) {
            *abort_more = true;
        }
        return MGMT_CB_ERROR_RC;
    }
    if (atomic_get(&physical_update_confirmation) == 0 ||
        atomic_get(&capture_idle_for_update) == 0) {
        *return_code = MGMT_ERR_EACCESSDENIED;
        *abort_more = true;
        return MGMT_CB_ERROR_RC;
    }
    return MGMT_CB_OK;
}

static struct mgmt_callback reset_admission_callback = {
    .callback = admit_physically_confirmed_reset,
    .event_id = MGMT_EVT_OP_OS_MGMT_RESET,
};

static void copy_status(
    uint8_t output[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES]
)
{
    k_spinlock_key_t key = k_spin_lock(&status_lock);

    memcpy(output, status_snapshot, sizeof(status_snapshot));
    k_spin_unlock(&status_lock, key);
}

static ssize_t read_status(
    struct bt_conn *connection,
    const struct bt_gatt_attr *attribute,
    void *buffer,
    uint16_t length,
    uint16_t offset
)
{
    uint8_t status[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES];

    copy_status(status);
    return bt_gatt_attr_read(
        connection,
        attribute,
        buffer,
        length,
        offset,
        status,
        sizeof(status)
    );
}

static ssize_t read_capability(
    struct bt_conn *connection,
    const struct bt_gatt_attr *attribute,
    void *buffer,
    uint16_t length,
    uint16_t offset
)
{
    return bt_gatt_attr_read(
        connection,
        attribute,
        buffer,
        length,
        offset,
        capability_snapshot,
        sizeof(capability_snapshot)
    );
}

static void status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
);

BT_GATT_SERVICE_DEFINE(
    gumi_omi_functional_identity_service,
    BT_GATT_PRIMARY_SERVICE(&omi_family_service_uuid)
);

BT_GATT_SERVICE_DEFINE(
    gumi_omi_functional_service,
    BT_GATT_PRIMARY_SERVICE(&functional_service_uuid),
    BT_GATT_CHARACTERISTIC(
        &functional_status_uuid.uuid,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ,
        read_status,
        NULL,
        NULL
    ),
    BT_GATT_CCC(status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
    BT_GATT_CHARACTERISTIC(
        &functional_capability_uuid.uuid,
        BT_GATT_CHRC_READ,
        BT_GATT_PERM_READ,
        read_capability,
        NULL,
        NULL
    )
);

static void notify_status(void)
{
    uint8_t status[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES];

    if (atomic_get(&transport_started) == 0) {
        return;
    }
    copy_status(status);
    (void)bt_gatt_notify(
        NULL,
        &gumi_omi_functional_service.attrs[1],
        status,
        sizeof(status)
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

static const struct bt_data advertising_data[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR),
    BT_DATA(
        BT_DATA_UUID128_ALL,
        omi_family_service_uuid.val,
        sizeof(omi_family_service_uuid.val)
    ),
    BT_DATA(
        BT_DATA_NAME_COMPLETE,
        CONFIG_BT_DEVICE_NAME,
        sizeof(CONFIG_BT_DEVICE_NAME) - 1U
    ),
};

static const struct bt_data scan_response_data[] = {
    BT_DATA(
        BT_DATA_UUID128_ALL,
        functional_service_uuid.val,
        sizeof(functional_service_uuid.val)
    ),
};

int gumi_omi_v3012_functional_transport_start(void)
{
    int error;

    if (!atomic_cas(&transport_started, 0, 1)) {
        return -EALREADY;
    }
    mgmt_callback_register(&image_admission_callback);
    mgmt_callback_register(&reset_admission_callback);
    error = bt_enable(NULL);
    if (error < 0) {
        atomic_set(&transport_started, 0);
        return error;
    }
    error = bt_le_adv_start(
        BT_LE_ADV_CONN,
        advertising_data,
        ARRAY_SIZE(advertising_data),
        scan_response_data,
        ARRAY_SIZE(scan_response_data)
    );
    if (error < 0) {
        atomic_set(&transport_started, 0);
        return error;
    }
    return 0;
}

int gumi_omi_v3012_functional_status_publish(
    const gumi_omi_v3012_functional_status *status
)
{
    uint8_t encoded[GUMI_OMI_V3012_FUNCTIONAL_STATUS_BYTES];
    k_spinlock_key_t key;
    int error;

    error = gumi_omi_v3012_functional_status_encode(status, encoded);
    if (error < 0) {
        return error;
    }
    key = k_spin_lock(&status_lock);
    memcpy(status_snapshot, encoded, sizeof(status_snapshot));
    k_spin_unlock(&status_lock, key);
    notify_status();
    return 0;
}

void gumi_omi_v3012_functional_update_admission_set(
    bool physically_confirmed,
    bool capture_idle
)
{
    atomic_set(
        &physical_update_confirmation,
        physically_confirmed ? 1 : 0
    );
    atomic_set(&capture_idle_for_update, capture_idle ? 1 : 0);
}
