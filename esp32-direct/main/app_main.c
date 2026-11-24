#include "dp_display.h"
#include "dp_frame_server.h"
#include "dp_wifi.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "sdkconfig.h"

#ifdef CONFIG_DIPLAY_BOOT_PATTERN
static const char *TAG = "roundnav_direct";
#endif

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    ESP_ERROR_CHECK(dp_display_init());
#ifdef CONFIG_DIPLAY_BOOT_PATTERN
    dp_display_boot_canvas(true);
    ESP_LOGI(TAG, "已提交开机测试图，上红、右绿、下蓝、左白");
#else
    dp_display_boot_canvas(false);
#endif

    ESP_ERROR_CHECK(dp_wifi_start());
    ESP_ERROR_CHECK(dp_frame_server_start());
}
