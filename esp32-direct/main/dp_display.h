#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#define DP_DISPLAY_WIDTH      466
#define DP_DISPLAY_HEIGHT     466
#define DP_DISPLAY_BLOCK_ROWS 16

/* 初始化 CO5300 AMOLED（走 BSP 的无 LVGL 接口）和两块内部 RAM DMA 缓冲。 */
esp_err_t dp_display_init(void);

/* 取一块空闲 DMA 缓冲（DP_DISPLAY_WIDTH * DP_DISPLAY_BLOCK_ROWS 像素），上一轮传输完成前会等待。 */
uint16_t *dp_display_acquire(void);

/* 把缓冲里的 w*h 像素异步刷到 (x, y)。x、y、w、h 都必须是偶数（CO5300 要求）。 */
esp_err_t dp_display_submit(uint16_t *buf, int x, int y, int w, int h);

/* 等待所有已提交的块传输完成。 */
void dp_display_wait_idle(void);

/* 清空全屏；诊断模式绘制圆环和用于检查字节序的四色标记。 */
void dp_display_boot_canvas(bool diagnostics);

/* 按当前字节序配置生成屏幕像素值。 */
uint16_t dp_display_rgb(uint8_t r, uint8_t g, uint8_t b);
