#include <stdio.h>
#include <string.h>
#include "dp_wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "sdkconfig.h"

static const char *TAG = "dp_wifi";

static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static char s_ip[16];
static bool s_has_ip;
static bool s_ap_mode;

static void set_ip(const char *ip)
{
    taskENTER_CRITICAL(&s_lock);
    if (ip) {
        strlcpy(s_ip, ip, sizeof(s_ip));
        s_has_ip = true;
    } else {
        s_has_ip = false;
    }
    taskEXIT_CRITICAL(&s_lock);
}

bool dp_wifi_get_ip(char *out, size_t len)
{
    bool ok;
    taskENTER_CRITICAL(&s_lock);
    ok = s_has_ip;
    if (ok) {
        strlcpy(out, s_ip, len);
    }
    taskEXIT_CRITICAL(&s_lock);
    return ok;
}

bool dp_wifi_is_ap(void)
{
    return s_ap_mode;
}

static void on_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        const wifi_event_sta_disconnected_t *d = data;
        set_ip(NULL);
        ESP_LOGW(TAG, "Wi-Fi 断开 (reason=%d)，2 秒后重连", d->reason);
        vTaskDelay(pdMS_TO_TICKS(2000));
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        const ip_event_got_ip_t *e = data;
        char ip[16];
        snprintf(ip, sizeof(ip), IPSTR, IP2STR(&e->ip_info.ip));
        set_ip(ip);
        wifi_ap_record_t ap;
        int rssi = 0;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) {
            rssi = ap.rssi;
        }
        ESP_LOGI(TAG, "已连上 %s，IP=%s，信号 %d dBm，推流端口 %d",
                 CONFIG_DIPLAY_WIFI_SSID, ip, rssi, CONFIG_DIPLAY_TCP_PORT);
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_AP_STACONNECTED) {
        ESP_LOGI(TAG, "有设备连上热点");
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_AP_STADISCONNECTED) {
        const wifi_event_ap_stadisconnected_t *event = data;
        ESP_LOGI(TAG, "有设备离开热点，原因码 %u", (unsigned)event->reason);
    }
}

esp_err_t dp_wifi_start(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    const bool use_sta = strlen(CONFIG_DIPLAY_WIFI_SSID) > 0;
    esp_netif_t *ap_netif = NULL;
    if (use_sta) {
        esp_netif_create_default_wifi_sta();
    } else {
        ap_netif = esp_netif_create_default_wifi_ap();
    }

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, on_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, on_event, NULL));

    wifi_config_t wc = {0};
    if (use_sta) {
        strlcpy((char *)wc.sta.ssid, CONFIG_DIPLAY_WIFI_SSID, sizeof(wc.sta.ssid));
        strlcpy((char *)wc.sta.password, CONFIG_DIPLAY_WIFI_PASSWORD, sizeof(wc.sta.password));
        wc.sta.threshold.authmode = WIFI_AUTH_OPEN; /* 兼容手机热点的 WPA2/WPA3 混合模式 */
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
        ESP_LOGI(TAG, "连接 Wi-Fi: %s", CONFIG_DIPLAY_WIFI_SSID);
    } else {
        s_ap_mode = true;
        ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
        if (strlen(CONFIG_DIPLAY_AP_SSID) > 0) {
            strlcpy((char *)wc.ap.ssid, CONFIG_DIPLAY_AP_SSID, sizeof(wc.ap.ssid));
        } else {
            uint8_t mac[6];
            ESP_ERROR_CHECK(esp_wifi_get_mac(WIFI_IF_AP, mac));
            snprintf((char *)wc.ap.ssid, sizeof(wc.ap.ssid), "RoundNav-%02X%02X",
                     (unsigned)mac[4], (unsigned)mac[5]);
        }
        wc.ap.ssid_len = strlen(CONFIG_DIPLAY_AP_SSID);
        strlcpy((char *)wc.ap.password, CONFIG_DIPLAY_AP_PASSWORD, sizeof(wc.ap.password));
        wc.ap.channel = CONFIG_DIPLAY_AP_CHANNEL;
        wc.ap.max_connection = 4;
        size_t password_len = strlen(CONFIG_DIPLAY_AP_PASSWORD);
        if (password_len > 0 && (password_len < 8 || password_len > 63)) {
            ESP_LOGE(TAG, "热点密码长度需为 8–63 字节");
            return ESP_ERR_INVALID_ARG;
        }
        wc.ap.authmode = password_len > 0 ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
        ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wc));
        esp_netif_ip_info_t info;
        ESP_ERROR_CHECK(esp_netif_get_ip_info(ap_netif, &info));
        char ip[16];
        snprintf(ip, sizeof(ip), IPSTR, IP2STR(&info.ip));
        set_ip(ip);
        ESP_LOGI(TAG, "开启热点 %s，IP=%s，推流端口 %d，信道 %u，认证 %s",
                 (char *)wc.ap.ssid, ip, CONFIG_DIPLAY_TCP_PORT, (unsigned)wc.ap.channel,
                 wc.ap.authmode == WIFI_AUTH_WPA2_PSK ? "WPA2-PSK" : "OPEN");
    }

    ESP_ERROR_CHECK(esp_wifi_start());
    /* 关闭省电，否则收包延迟会周期性跳到 100 ms 以上。 */
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    return ESP_OK;
}
