#include "gumi/omi_v3012_recovery.h"

#include <errno.h>
#include <stdint.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
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

/* Gumi recovery status service: 796e0485-8f9d-4063-af3b-f5596fced74a. */
static struct bt_uuid_128 gumi_recovery_service_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x796E0485,
        0x8F9D,
        0x4063,
        0xAF3B,
        0xF5596FCED74A
    ));

/* Read-only status characteristic: 32fcb4a7-660b-4c26-a887-3baf0166246c. */
static struct bt_uuid_128 gumi_recovery_status_uuid =
    BT_UUID_INIT_128(BT_UUID_128_ENCODE(
        0x32FCB4A7,
        0x660B,
        0x4C26,
        0xA887,
        0x3BAF0166246C
    ));

/* Wire bytes [1, COLD, NONE, overwrite-only] packed little-endian. */
static atomic_t gumi_recovery_status_word = ATOMIC_INIT(0x20000001);
static atomic_t gumi_recovery_transport_started = ATOMIC_INIT(0);

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
    if (event != MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK ||
        return_code == NULL || abort_more == NULL || check == NULL ||
        data_size != sizeof(*check) || check->req == NULL) {
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

static void unpack_status(uint8_t output[GUMI_RECOVERY_STATUS_WIRE_SIZE])
{
    uint32_t word = (uint32_t)atomic_get(&gumi_recovery_status_word);

    output[0] = (uint8_t)(word & UINT32_C(0xff));
    output[1] = (uint8_t)((word >> 8) & UINT32_C(0xff));
    output[2] = (uint8_t)((word >> 16) & UINT32_C(0xff));
    output[3] = (uint8_t)((word >> 24) & UINT32_C(0xff));
}

static ssize_t read_recovery_status(
    struct bt_conn *connection,
    const struct bt_gatt_attr *attribute,
    void *buffer,
    uint16_t length,
    uint16_t offset
)
{
    uint8_t status[GUMI_RECOVERY_STATUS_WIRE_SIZE];

    unpack_status(status);
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

static void recovery_status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
);

/*
 * The recovery image advertises the stock family discriminator, so it also exposes that primary
 * service UUID. It intentionally has no audio characteristics: full driver negotiation therefore
 * fails closed instead of mistaking recovery mode for a capture-capable session.
 */
BT_GATT_SERVICE_DEFINE(
    gumi_omi_identity_service,
    BT_GATT_PRIMARY_SERVICE(&omi_audio_service_uuid)
);

BT_GATT_SERVICE_DEFINE(
    gumi_recovery_service,
    BT_GATT_PRIMARY_SERVICE(&gumi_recovery_service_uuid),
    BT_GATT_CHARACTERISTIC(
        &gumi_recovery_status_uuid.uuid,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ,
        read_recovery_status,
        NULL,
        NULL
    ),
    BT_GATT_CCC(recovery_status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE)
);

static void notify_recovery_status(void)
{
    uint8_t status[GUMI_RECOVERY_STATUS_WIRE_SIZE];

    if (atomic_get(&gumi_recovery_transport_started) == 0) {
        return;
    }
    unpack_status(status);
    (void)bt_gatt_notify(
        NULL,
        &gumi_recovery_service.attrs[1],
        status,
        sizeof(status)
    );
}

static void recovery_status_ccc_changed(
    const struct bt_gatt_attr *attribute,
    uint16_t value
)
{
    ARG_UNUSED(attribute);

    if (value == BT_GATT_CCC_NOTIFY) {
        notify_recovery_status();
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
        gumi_recovery_service_uuid.val,
        sizeof(gumi_recovery_service_uuid.val)
    ),
};

int gumi_omi_v3012_recovery_transport_start(void)
{
    int error;

    if (!atomic_cas(&gumi_recovery_transport_started, 0, 1)) {
        return -EALREADY;
    }

    mgmt_callback_register(&application_image_only_callback);
    error = bt_enable(NULL);
    if (error < 0) {
        atomic_set(&gumi_recovery_transport_started, 0);
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
        return error;
    }
    return 0;
}

int gumi_omi_v3012_recovery_status_publish(
    const gumi_recovery_supervisor *supervisor
)
{
    uint8_t status[GUMI_RECOVERY_STATUS_WIRE_SIZE];
    uint32_t word;

    if (gumi_recovery_encode_status(supervisor, status) != GUMI_RECOVERY_STATUS_OK) {
        return -EINVAL;
    }
    word = (uint32_t)status[0] |
           ((uint32_t)status[1] << 8) |
           ((uint32_t)status[2] << 16) |
           ((uint32_t)status[3] << 24);
    atomic_set(&gumi_recovery_status_word, (atomic_val_t)word);
    notify_recovery_status();
    return 0;
}
