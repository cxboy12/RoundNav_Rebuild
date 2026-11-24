#include <string.h>
#include "dp_display.h"
#include "bsp/display.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "sdkconfig.h"

_Static_assert(DP_DISPLAY_WIDTH == BSP_LCD_H_RES, "display width must match BSP");
_Static_assert(DP_DISPLAY_HEIGHT == BSP_LCD_V_RES, "display height must match BSP");

#define DMA_BUF_COUNT  2
#define DMA_BUF_PIXELS (DP_DISPLAY_WIDTH * DP_DISPLAY_BLOCK_ROWS)

#if CONFIG_DIPLAY_RGB565_BIG_ENDIAN
#define DP_BYTE_ORDER_TEXT "RGB565 大端"
#else
#define DP_BYTE_ORDER_TEXT "RGB565 小端"
#endif

static const char *TAG = "dp_display";

static esp_lcd_panel_handle_t s_panel;
static esp_lcd_panel_io_handle_t s_io;
static SemaphoreHandle_t s_free_bufs;
static uint16_t *s_bufs[DMA_BUF_COUNT];
static int s_next_buf;

/* 每次 draw_bitmap 的像素传输结束时触发一次。块按顺序完成，所以用计数信号量轮转两块缓冲即可。 */
static bool IRAM_ATTR on_color_trans_done(esp_lcd_panel_io_handle_t io, esp_lcd_panel_io_event_data_t *edata, void *user_ctx)
{
    BaseType_t woken = pdFALSE;
    xSemaphoreGiveFromISR(s_free_bufs, &woken);
    return woken == pdTRUE;
}

uint16_t dp_display_rgb(uint8_t r, uint8_t g, uint8_t b)
{
    uint16_t v = (uint16_t)(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
#if CONFIG_DIPLAY_RGB565_BIG_ENDIAN
    return (uint16_t)((v >> 8) | (v << 8));
#else
    return v;
#endif
}

esp_err_t dp_display_init(void)
{
    /* 每块 16 行正好是一次 SPI 事务。缓冲放内部 RAM：RoundNav 实测这块板直接从 PSRAM 做 DMA 会出错。 */
    const bsp_display_config_t cfg = {
        .max_transfer_sz = DMA_BUF_PIXELS * sizeof(uint16_t),
    };
    ESP_RETURN_ON_ERROR(bsp_display_new(&cfg, &s_panel, &s_io), TAG, "bsp_display_new failed");

    s_free_bufs = xSemaphoreCreateCounting(DMA_BUF_COUNT, DMA_BUF_COUNT);
    ESP_RETURN_ON_FALSE(s_free_bufs != NULL, ESP_ERR_NO_MEM, TAG, "no memory for semaphore");

    for (int i = 0; i < DMA_BUF_COUNT; i++) {
        s_bufs[i] = heap_caps_malloc(DMA_BUF_PIXELS * sizeof(uint16_t), MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
        ESP_RETURN_ON_FALSE(s_bufs[i] != NULL, ESP_ERR_NO_MEM, TAG, "no internal DMA memory");
    }

    const esp_lcd_panel_io_callbacks_t cbs = {
        .on_color_trans_done = on_color_trans_done,
    };
    ESP_RETURN_ON_ERROR(esp_lcd_panel_io_register_event_callbacks(s_io, &cbs, NULL), TAG, "register callback failed");

    bsp_display_brightness_set(CONFIG_DIPLAY_BRIGHTNESS);

    ESP_LOGI(TAG, "屏幕就绪 %dx%d，分块 %d 行，%d 块内部 DMA 缓冲，字节序 %s",
             DP_DISPLAY_WIDTH, DP_DISPLAY_HEIGHT, DP_DISPLAY_BLOCK_ROWS, DMA_BUF_COUNT,
             DP_BYTE_ORDER_TEXT);
    return ESP_OK;
}

uint16_t *dp_display_acquire(void)
{
    if (xSemaphoreTake(s_free_bufs, pdMS_TO_TICKS(1000)) != pdTRUE) {
        ESP_LOGE(TAG, "等待屏幕 DMA 缓冲超时");
        return NULL;
    }
    uint16_t *buf = s_bufs[s_next_buf];
    s_next_buf = (s_next_buf + 1) % DMA_BUF_COUNT;
    return buf;
}

esp_err_t dp_display_submit(uint16_t *buf, int x, int y, int w, int h)
{
    esp_err_t err = esp_lcd_panel_draw_bitmap(s_panel, x, y, x + w, y + h, buf);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "draw_bitmap 失败: %s", esp_err_to_name(err));
        xSemaphoreGive(s_free_bufs);
    }
    return err;
}

void dp_display_wait_idle(void)
{
    int taken = 0;
    for (int i = 0; i < DMA_BUF_COUNT; i++) {
        if (xSemaphoreTake(s_free_bufs, pdMS_TO_TICKS(1000)) == pdTRUE) {
            taken++;
        }
    }
    for (int i = 0; i < taken; i++) {
        xSemaphoreGive(s_free_bufs);
    }
}

static inline int sq(int v)
{
    return v * v;
}

void dp_display_boot_canvas(bool diagnostics)
{
    const uint16_t bg = dp_display_rgb(0, 0, 0);
    const uint16_t ring = dp_display_rgb(0, 200, 255);
    const uint16_t red = dp_display_rgb(255, 0, 0);
    const uint16_t green = dp_display_rgb(0, 255, 0);
    const uint16_t blue = dp_display_rgb(0, 0, 255);
    const uint16_t white = dp_display_rgb(255, 255, 255);
    const uint16_t grey = dp_display_rgb(110, 110, 110);
    const int c2 = DP_DISPLAY_WIDTH - 1; /* 用 2 倍坐标避免中心点 232.5 的小数 */

    for (int y0 = 0; y0 < DP_DISPLAY_HEIGHT; y0 += DP_DISPLAY_BLOCK_ROWS) {
        int rows = DP_DISPLAY_HEIGHT - y0;
        if (rows > DP_DISPLAY_BLOCK_ROWS) {
            rows = DP_DISPLAY_BLOCK_ROWS;
        }
        uint16_t *buf = dp_display_acquire();
        if (buf == NULL) {
            return;
        }
        if (!diagnostics) {
            memset(buf, 0, (size_t)rows * DP_DISPLAY_WIDTH * sizeof(*buf));
            if (dp_display_submit(buf, 0, y0, DP_DISPLAY_WIDTH, rows) != ESP_OK) {
                return;
            }
            continue;
        }
        for (int r = 0; r < rows; r++) {
            const int dy = 2 * (y0 + r) - c2;
            uint16_t *line = buf + r * DP_DISPLAY_WIDTH;
            for (int x = 0; x < DP_DISPLAY_WIDTH; x++) {
                const int dx = 2 * x - c2;
                const int d2 = sq(dx) + sq(dy);
                uint16_t c = bg;
                if (d2 >= sq(440) && d2 <= sq(460)) {
                    c = ring;                                   /* 半径 220~230 的外圈 */
                } else if (sq(dx) + sq(dy + 300) <= sq(44)) {
                    c = red;                                    /* 上 */
                } else if (sq(dx - 300) + sq(dy) <= sq(44)) {
                    c = green;                                  /* 右 */
                } else if (sq(dx) + sq(dy - 300) <= sq(44)) {
                    c = blue;                                   /* 下 */
                } else if (sq(dx + 300) + sq(dy) <= sq(44)) {
                    c = white;                                  /* 左 */
                } else if ((dx >= -3 && dx <= 3 && dy >= -60 && dy <= 60) ||
                           (dy >= -3 && dy <= 3 && dx >= -60 && dx <= 60)) {
                    c = grey;                                   /* 中心十字 */
                }
                line[x] = c;
            }
        }
        if (dp_display_submit(buf, 0, y0, DP_DISPLAY_WIDTH, rows) != ESP_OK) {
            return;
        }
    }
    dp_display_wait_idle();
}
