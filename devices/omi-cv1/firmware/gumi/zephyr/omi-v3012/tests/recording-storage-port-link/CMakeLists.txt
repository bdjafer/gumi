cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(gumi_omi_v3012_recording_storage_port_link)

if(NOT DEFINED GUMI_FIRMWARE_DIR)
    message(FATAL_ERROR "GUMI_FIRMWARE_DIR is required")
endif()

target_sources(app PRIVATE
    src/main.c
    ${GUMI_FIRMWARE_DIR}/src/recording_journal.c
    ${GUMI_FIRMWARE_DIR}/src/recording_store.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/crypto_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_key_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_storage_pm_policy.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_storage_port.c
)
target_include_directories(app PRIVATE
    ${GUMI_FIRMWARE_DIR}/include
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/include
)
