cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
enable_language(C ASM)
project(gumi_omi_v3012_functional_recording_link)

if(NOT DEFINED GUMI_FIRMWARE_DIR)
    get_filename_component(
        GUMI_FIRMWARE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../.."
        ABSOLUTE
    )
endif()
if(NOT DEFINED OMI_OPUS_DIR AND DEFINED ENV{OMI_OPUS_DIR})
    set(OMI_OPUS_DIR "$ENV{OMI_OPUS_DIR}")
endif()
if(NOT DEFINED OMI_OPUS_DIR OR NOT EXISTS "${OMI_OPUS_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "OMI_OPUS_DIR is required")
endif()

target_sources(app PRIVATE
    ${GUMI_FIRMWARE_DIR}/src/button.c
    ${GUMI_FIRMWARE_DIR}/src/capture.c
    ${GUMI_FIRMWARE_DIR}/src/feedback.c
    ${GUMI_FIRMWARE_DIR}/src/recording_journal.c
    ${GUMI_FIRMWARE_DIR}/src/recording_store.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/codec_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/crypto_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/functional_io.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/functional_main.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/functional_transport.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/mic_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_key_port.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_storage_pm_policy.c
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/src/recording_storage_port.c
)
target_include_directories(app PRIVATE
    ${GUMI_FIRMWARE_DIR}/include
    ${GUMI_FIRMWARE_DIR}/zephyr/omi-v3012/include
    ${OMI_OPUS_DIR}
)

add_subdirectory(${OMI_OPUS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/opus)
target_link_libraries(app PRIVATE opus_codec)
