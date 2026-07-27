#include "gumi/omi_v3012_capture_selftest.h"

#include <errno.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt.h>
#include <zephyr/mgmt/mcumgr/grp/img_mgmt/img_mgmt_callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/callbacks.h>
#include <zephyr/mgmt/mcumgr/mgmt/mgmt_defines.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/util.h>

/* Existing scanners identify an Omi CV1 by this advertised service UUID. */
static struct bt_uuid_128 omi_audio_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x19B10000,
        0xE8F2,
        0x537E,
        0x4F6C,
        0xD104768A1214
    ));

/* Capture-port self-test service: f80a6e60-3b3f-4e8a-93e4-5f5e2c527001. */
static struct bt_uuid_128 selftest_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0xF80A6E60,
        0x3B3F,
        0x4E8A,
        0x93E4,
        0x5F5E2C527001
    ));

/* Read/notify media-free status: f80a6e61-3b3f-4e8a-93e4-5f5e2c527001. */
static struct bt_uuid_128 selftest_status_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0xF80A6E61,
        0x3B3F,
        0x4E8A,
        0x93E4,
        0x5F5E2C527001
    ));

/* Write exactly byte 0x01 to arm: f80a6e62-3b3f-4e8a-93e4-5f5e2c527001. */
static struct bt_uuid_128 selftest_arm_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0xF80A6E62,
        0x3B3F,
        0x4E8A,
        0x93E4,
        0x5F5E2C527001
    ));

static uint8_t status_snapshot[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE] = {
    GUMI_CAPTURE_SELFTEST_STATUS_WIRE_VERSION,
    (uint8_t)GUMI_CAPTURE_SELFTEST_PHASE_FAILED_SAFE,
    (uint8_t)GUMI_CAPTURE_SELFTEST_FAILURE_ASYNC_PORT,
    UINT8_C(0x80),
};
static struct k_spinlock status_lock;
static atomic_t transport_started = ATOMIC_INIT(0);
static atomic_t arm_pending = ATOMIC_INIT(0);

static enum mgmt_cb_return admit_application_image_only(
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
    if (check->req->image != 0U) {
        *return_code = MGMT_ERR_EACCESSDENIED;
        *abort_more = true;
        return MGMT_CB_ERROR_RC;
    }
    return MGMT_CB_OK;
}

static struct mgmt_callback application_image_only_callback = {
    .callback = admit_application_image_only,
    .event_id = MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK,
};

static void copy_status(uint8_t output[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE])
{
    k_spinlock_key_t key = k_spin_lock(&status_lock);

    memcpy(output, status_snapshot, GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE);
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
    uint8_t status[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE];

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

static ssize_t write_arm(
    struct bt_conn *connection,
    const struct bt_gatt_attr *attribute,
    const void *buffer,
    uint16_t length,
    uint16_t offset,
    uint8_t flags
)
{
    const uint8_t *bytes = buffer;

    ARG_UNUSED(connection);
    ARG_UNUSED(attribute);
    ARG_UNUSED(flags);
    if (buffer == NULL || length != 1U || offset != 0U) {
        return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
    }
    if (bytes[0] != UINT8_C(1)) {
        return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
    }
    if (!atomic_cas(&arm_pending, 0, 1)) {
        return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
    }
    return (ssize_t)length;
}

static void status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
);

/* Family discriminator only. No stock audio characteristic exists. */
BT_GATT_SERVICE_DEFINE(
    gumi_omi_identity_service,
    BT_GATT_PRIMARY_SERVICE(&omi_audio_service_uuid)
);

BT_GATT_SERVICE_DEFINE(
    gumi_capture_selftest_service,
    BT_GATT_PRIMARY_SERVICE(&selftest_service_uuid),
    BT_GATT_CHARACTERISTIC(
        &selftest_status_uuid.uuid,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ,
        read_status,
        NULL,
        NULL
    ),
    BT_GATT_CCC(status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
    BT_GATT_CHARACTERISTIC(
        &selftest_arm_uuid.uuid,
        BT_GATT_CHRC_WRITE,
        BT_GATT_PERM_WRITE,
        NULL,
        write_arm,
        NULL
    )
);

static void notify_status(void)
{
    uint8_t status[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE];

    if (atomic_get(&transport_started) == 0) {
        return;
    }
    copy_status(status);
    (void)bt_gatt_notify(
        NULL,
        &gumi_capture_selftest_service.attrs[1],
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
        omi_audio_service_uuid.val,
        sizeof(omi_audio_service_uuid.val)
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
        selftest_service_uuid.val,
        sizeof(selftest_service_uuid.val)
    ),
};

int gumi_omi_v3012_capture_selftest_transport_start(void)
{
    int error;

    if (!atomic_cas(&transport_started, 0, 1)) {
        return -EALREADY;
    }
    mgmt_callback_register(&application_image_only_callback);
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

bool gumi_omi_v3012_capture_selftest_take_arm_request(void)
{
    return atomic_cas(&arm_pending, 1, 0);
}

int gumi_omi_v3012_capture_selftest_status_publish(
    const gumi_capture_selftest *supervisor
)
{
    uint8_t status[GUMI_CAPTURE_SELFTEST_STATUS_WIRE_SIZE];
    k_spinlock_key_t key;

    if (gumi_capture_selftest_encode_status(supervisor, status) !=
        GUMI_CAPTURE_SELFTEST_STATUS_OK) {
        return -EINVAL;
    }
    key = k_spin_lock(&status_lock);
    memcpy(status_snapshot, status, sizeof(status_snapshot));
    k_spin_unlock(&status_lock, key);
    notify_status();
    return 0;
}
