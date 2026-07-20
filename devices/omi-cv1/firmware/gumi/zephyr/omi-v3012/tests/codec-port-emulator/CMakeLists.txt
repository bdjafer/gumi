cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(gumi_omi_v3012_codec_port_emulator)

if(NOT DEFINED GUMI_CODEC_PORT_DIR)
    message(FATAL_ERROR "GUMI_CODEC_PORT_DIR is required")
endif()
if(NOT DEFINED OMI_OPUS_DIR)
    message(FATAL_ERROR "OMI_OPUS_DIR is required")
endif()

target_sources(app PRIVATE
    src/main.c
    ${GUMI_CODEC_PORT_DIR}/src/codec_port.c
)
target_include_directories(app PRIVATE
    ${GUMI_CODEC_PORT_DIR}/include
    ${OMI_OPUS_DIR}
)

add_subdirectory(${OMI_OPUS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/opus)
target_link_libraries(app PRIVATE opus_codec)
