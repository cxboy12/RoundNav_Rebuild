#include <string.h>
#include "esp_log.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "bsp/display.h"
#include "bsp/esp32_s3_touch_amoled_1_75.h"
#include "roundnav_events.h"
#include "roundnav_ble.h"
#include "nav_ui.h"

static const char *TAG = "RoundNav";
#define UI_QUEUE_LENGTH 512
static QueueHandle_t s_ui_queue;

void app_main(void)
{
    esp_err_t nvs = nvs_flash_init();
    if (nvs == ESP_ERR_NVS_NO_FREE_PAGES || nvs == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        nvs = nvs_flash_init();
    }
    ESP_ERROR_CHECK(nvs);

    s_ui_queue = xQueueCreate(UI_QUEUE_LENGTH, sizeof(roundnav_event_t));
    if (s_ui_queue == NULL) {
        ESP_LOGE(TAG, "Cannot create UI event queue");
        return;
    }

    ESP_LOGI(TAG, "Starting Waveshare 1.75-inch AMOLED display");
    if (bsp_display_start() == NULL) {
        ESP_LOGE(TAG, "bsp_display_start failed");
        return;
    }
    nav_ui_init();
    ESP_ERROR_CHECK(roundnav_ble_init(s_ui_queue));
    ESP_LOGI(TAG, "Ready. BLE name=RoundNav; GPS command: G,lat_e6,lon_e6,bearing_deg");

    roundnav_event_t event;
    while (true) {
        if (xQueueReceive(s_ui_queue, &event, portMAX_DELAY) != pdTRUE) continue;
        switch (event.type) {
        case ROUNDNAV_EVENT_NAV:        nav_ui_apply_nav(&event); break;
        case ROUNDNAV_EVENT_LINK:       nav_ui_set_ble_state(event.connected); break;
        case ROUNDNAV_EVENT_MAP_BEGIN:
        case ROUNDNAV_EVENT_MAP_POINT:
        case ROUNDNAV_EVENT_MAP_END:    nav_ui_apply_map_event(&event); break;
        case ROUNDNAV_EVENT_ROAD_CENTER:
        case ROUNDNAV_EVENT_ROAD_BEGIN:
        case ROUNDNAV_EVENT_ROAD_POINT:
        case ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN:
        case ROUNDNAV_EVENT_ROAD_SNAPSHOT_END:
        case ROUNDNAV_EVENT_ROAD_END:   nav_ui_apply_road_event(&event); break;
        case ROUNDNAV_EVENT_GPS:        nav_ui_apply_gps(&event); break;
        default: break;
        }
    }
}
