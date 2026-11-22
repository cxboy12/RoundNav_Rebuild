idf_component_register(
    SRCS "app_main.c" "roundnav_ble.c" "nav_ui.c" "road_store.c"
    INCLUDE_DIRS "."
    REQUIRES bt nvs_flash lvgl esp32_s3_touch_amoled_1_75
)
