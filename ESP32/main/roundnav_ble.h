#pragma once
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "esp_err.h"
esp_err_t roundnav_ble_init(QueueHandle_t event_queue);
