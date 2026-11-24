#pragma once

#include "esp_err.h"

/* 启动 TCP 推流服务、JPEG 解码刷屏任务和局域网发现广播。 */
esp_err_t dp_frame_server_start(void);
