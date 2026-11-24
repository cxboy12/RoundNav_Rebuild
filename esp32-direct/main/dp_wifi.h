#pragma once

#include <stdbool.h>
#include <stddef.h>
#include "esp_err.h"

/* 配置了 DIPLAY_WIFI_SSID 时连接该 Wi-Fi；留空时自建热点 DIPLAY_AP_SSID（IP 192.168.4.1）。 */
esp_err_t dp_wifi_start(void);

/* 有可用 IP 时返回 true 并写入点分十进制字符串。 */
bool dp_wifi_get_ip(char *out, size_t len);

/* 当前是否为热点模式。 */
bool dp_wifi_is_ap(void);
