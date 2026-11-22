#include <string.h>
#include "esp_log.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
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
    bsp_display_cfg_t display_config = {
        .lv_adapter_cfg = ESP_LV_ADAPTER_DEFAULT_CONFIG(),
        .rotation = ESP_LV_ADAPTER_ROTATE_0,
        .tear_avoid_mode = ESP_LV_ADAPTER_TEAR_AVOID_MODE_NONE,
        .touch_flags = { .swap_xy = 0, .mirror_x = 1, .mirror_y = 1 },
    };
    /* A 1 ms delay rounds down to zero at CONFIG_FREERTOS_HZ=100.
     * Leave a full tick for the queued BLE consumer between render passes. */
    display_config.lv_adapter_cfg.task_min_delay_ms = portTICK_PERIOD_MS;
    if (bsp_display_start_with_config(&display_config) == NULL) {
        ESP_LOGE(TAG, "bsp_display_start failed");
        return;
    }
    nav_ui_init();
    ESP_ERROR_CHECK(roundnav_ble_init(s_ui_queue));
    /* Let the event consumer acquire the released mutex before the display
     * worker starts another frame. The consumer blocks on its queue when idle. */
    vTaskPrioritySet(NULL, display_config.lv_adapter_cfg.task_priority + 1);
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
