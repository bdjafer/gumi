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

# The pinned Omi Opus tree's config.h imports the physical board's GPIO
# configuration. The emulator uses the same codec sources and compile options,
# but supplies only the codec-mode values so the test cannot accidentally depend
# on Omi board headers or peripherals.
get_target_property(OPUS_COMPILE_DEFINITIONS opus_codec COMPILE_DEFINITIONS)
list(REMOVE_ITEM OPUS_COMPILE_DEFINITIONS HAVE_CONFIG_H)
set_target_properties(opus_codec PROPERTIES COMPILE_DEFINITIONS "${OPUS_COMPILE_DEFINITIONS}")
target_compile_definitions(opus_codec PRIVATE
    CONFIG_OPUS_MODE_CELT=1
    CONFIG_OPUS_MODE_SILK=2
    CONFIG_OPUS_MODE=1
)
target_link_libraries(app PRIVATE opus_codec)
