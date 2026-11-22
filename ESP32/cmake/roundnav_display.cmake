# BSP 3.0.1 hardcodes a 20-row transfer and draw buffer. Build a configured
# copy so dependency downloads remain intact and clean checkouts reproduce it.
idf_component_get_property(roundnav_bsp_dir waveshare__esp32_s3_touch_amoled_1_75 COMPONENT_DIR)
idf_component_get_property(roundnav_bsp_lib waveshare__esp32_s3_touch_amoled_1_75 COMPONENT_LIB)
set(roundnav_bsp_source "${roundnav_bsp_dir}/esp32_s3_touch_amoled_1_75.c")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${roundnav_bsp_source}")
file(READ "${roundnav_bsp_source}" roundnav_bsp_code)

function(roundnav_replace_bsp original replacement)
    string(FIND "${roundnav_bsp_code}" "${original}" match_position)
    if(match_position EQUAL -1)
        message(FATAL_ERROR "RoundNav display adaptation no longer matches the installed BSP: ${original}")
    endif()
    string(REPLACE "${original}" "${replacement}" roundnav_bsp_code "${roundnav_bsp_code}")
    set(roundnav_bsp_code "${roundnav_bsp_code}" PARENT_SCOPE)
endfunction()

roundnav_replace_bsp(
    ".max_transfer_sz = BSP_LCD_H_RES * 20 * BSP_LCD_BITS_PER_PIXEL / 8,"
    ".max_transfer_sz = BSP_LCD_H_RES * 320 * BSP_LCD_BITS_PER_PIXEL / 8,")
roundnav_replace_bsp(".buffer_height = 20," ".buffer_height = 320,")
roundnav_replace_bsp(
    "io_config.trans_queue_depth = CONFIG_BSP_LCD_TRANS_QUEUE_DEPTH;"
    "io_config.trans_queue_depth = 1;
    io_config.flags.psram_dma_direct = true;
    ESP_LOGI(TAG, \"RoundNav display: rows=320, queue=1, direct PSRAM DMA\");")

set(roundnav_configured_bsp "${CMAKE_BINARY_DIR}/roundnav_bsp/esp32_s3_touch_amoled_1_75.c")
get_filename_component(roundnav_configured_dir "${roundnav_configured_bsp}" DIRECTORY)
file(MAKE_DIRECTORY "${roundnav_configured_dir}")
# Avoid touching the generated source on a no-op CMake reconfiguration.
set(roundnav_bsp_template "${roundnav_configured_dir}/bsp.c.in")
file(WRITE "${roundnav_bsp_template}" "${roundnav_bsp_code}")
configure_file("${roundnav_bsp_template}" "${roundnav_configured_bsp}" COPYONLY)

get_target_property(roundnav_bsp_sources ${roundnav_bsp_lib} SOURCES)
list(FIND roundnav_bsp_sources "${roundnav_bsp_source}" roundnav_source_index)
if(roundnav_source_index EQUAL -1)
    message(FATAL_ERROR "RoundNav cannot locate the BSP display translation unit")
endif()
list(REMOVE_AT roundnav_bsp_sources ${roundnav_source_index})
list(APPEND roundnav_bsp_sources "${roundnav_configured_bsp}")
set_property(TARGET ${roundnav_bsp_lib} PROPERTY SOURCES "${roundnav_bsp_sources}")
