idf_component_register(
    SRCS "app_main.c" "dp_display.c" "dp_wifi.c" "dp_frame_server.c"
    INCLUDE_DIRS "."
    REQUIRES esp_wifi esp_netif esp_event nvs_flash esp_lcd esp_timer lwip
             esp32_s3_touch_amoled_1_75 esp_new_jpeg
)
