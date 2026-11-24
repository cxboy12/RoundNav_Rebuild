#include <errno.h>
#include <stdio.h>
#include <string.h>
#include "dp_frame_server.h"
#include "dp_display.h"
#include "dp_protocol.h"
#include "dp_wifi.h"
#include "esp_heap_caps.h"
#include "esp_jpeg_dec.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"
#include "sdkconfig.h"

#define SLOT_COUNT        3
#define SLOT_CAPACITY     (CONFIG_DIPLAY_MAX_JPEG_KB * 1024)
#define BLOCK_BYTES       (DP_DISPLAY_WIDTH * DP_DISPLAY_BLOCK_ROWS * 2)
#define IDLE_TIMEOUT_S    5
#define STATS_PERIOD_US   (5 * 1000 * 1000)

#if CONFIG_DIPLAY_RGB565_BIG_ENDIAN
#define DP_JPEG_OUT_FORMAT JPEG_PIXEL_FORMAT_RGB565_BE
#else
#define DP_JPEG_OUT_FORMAT JPEG_PIXEL_FORMAT_RGB565_LE
#endif

typedef struct {
    uint8_t *data;
    uint32_t len;
    uint32_t frame_id;
    uint32_t gen;
    uint16_t x;
    uint16_t y;
} frame_slot_t;

typedef struct {
    uint32_t frames;
    uint32_t skipped;
    uint32_t errors;
    uint64_t bytes;
    uint64_t decode_us;
    uint64_t display_submit_us;
    uint64_t frame_us;
    int64_t since_us;
} frame_stats_t;

static const char *TAG = "dp_server";

static frame_slot_t s_slots[SLOT_COUNT];
static QueueHandle_t s_free_q;
static QueueHandle_t s_ready_q;
static SemaphoreHandle_t s_sock_lock;
static int s_client = -1;
static uint32_t s_gen;
static uint8_t *s_block;
static uint8_t s_drain_buf[1460];
static portMUX_TYPE s_stats_lock = portMUX_INITIALIZER_UNLOCKED;
static uint32_t s_received;
static uint32_t s_rejected;

static int recv_all(int sock, void *buf, size_t len)
{
    uint8_t *p = buf;
    while (len > 0) {
        int r = recv(sock, p, len, 0);
        if (r <= 0) {
            return -1;
        }
        p += r;
        len -= (size_t)r;
    }
    return 0;
}

static int send_all(int sock, const void *buf, size_t len)
{
    const uint8_t *p = buf;
    while (len > 0) {
        int r = send(sock, p, len, 0);
        if (r <= 0) {
            return -1;
        }
        p += r;
        len -= (size_t)r;
    }
    return 0;
}

static int drain(int sock, uint32_t len)
{
    while (len > 0) {
        size_t n = len < sizeof(s_drain_buf) ? len : sizeof(s_drain_buf);
        if (recv_all(sock, s_drain_buf, n) != 0) {
            return -1;
        }
        len -= (uint32_t)n;
    }
    return 0;
}

/* 只有当连接代次没变时才回 ACK，避免把旧连接的 ACK 发给新连接。 */
static void send_ack(uint32_t gen, uint32_t frame_id, uint16_t status, uint32_t decode_us, uint32_t frame_us)
{
    dp_ack_t ack;
    memcpy(ack.magic, DP_MAGIC_ACK, 4);
    ack.frame_id = frame_id;
    ack.status = status;
    ack.queued = (uint16_t)uxQueueMessagesWaiting(s_ready_q);
    ack.decode_us = decode_us;
    ack.frame_us = frame_us;

    xSemaphoreTake(s_sock_lock, portMAX_DELAY);
    if (s_client >= 0 && gen == s_gen) {
        if (send_all(s_client, &ack, sizeof(ack)) != 0) {
            shutdown(s_client, SHUT_RDWR);
        }
    }
    xSemaphoreGive(s_sock_lock);
}

static uint16_t decode_and_draw(const frame_slot_t *slot, uint32_t *decode_us, uint32_t *display_submit_us)
{
    jpeg_dec_config_t cfg = DEFAULT_JPEG_DEC_CONFIG();
    jpeg_dec_handle_t dec = NULL;
    jpeg_dec_io_t io = {0};
    jpeg_dec_header_info_t info = {0};
    uint16_t status = DP_ST_OK;
    int outbuf_len = 0;
    int process_count = 0;
    int w = 0;
    int h = 0;
    int row = 0;

    cfg.output_type = DP_JPEG_OUT_FORMAT;
    cfg.block_enable = true; /* 每次输出 8/16 行，边解码边刷屏，不需要整帧 PSRAM 缓冲 */
    if (jpeg_dec_open(&cfg, &dec) != JPEG_ERR_OK) {
        return DP_ST_BAD_JPEG;
    }

    io.inbuf = slot->data;
    io.inbuf_len = (int)slot->len;
    if (jpeg_dec_parse_header(dec, &io, &info) != JPEG_ERR_OK) {
        status = DP_ST_BAD_JPEG;
        goto out;
    }

    w = info.width;
    h = info.height;
    if (w <= 0 || h <= 0 || (w % DP_JPEG_ALIGNMENT) != 0 || (h % DP_JPEG_ALIGNMENT) != 0 ||
        (slot->x & 1) != 0 || (slot->y & 1) != 0 ||
        slot->x + w > DP_DISPLAY_WIDTH || slot->y + h > DP_DISPLAY_HEIGHT) {
        ESP_LOGW(TAG, "帧 %lu 尺寸不符: %dx%d @ (%u,%u)", (unsigned long)slot->frame_id, w, h, slot->x, slot->y);
        status = DP_ST_BAD_GEOMETRY;
        goto out;
    }

    if (jpeg_dec_get_outbuf_len(dec, &outbuf_len) != JPEG_ERR_OK || outbuf_len <= 0 || outbuf_len > BLOCK_BYTES ||
        jpeg_dec_get_process_count(dec, &process_count) != JPEG_ERR_OK || process_count <= 0) {
        ESP_LOGW(TAG, "帧 %lu 分块参数异常: outbuf=%d count=%d", (unsigned long)slot->frame_id, outbuf_len, process_count);
        status = DP_ST_BAD_JPEG;
        goto out;
    }

    io.outbuf = s_block;
    row = slot->y;
    for (int i = 0; i < process_count && row < slot->y + h; i++) {
        const int64_t t0 = esp_timer_get_time();
        const jpeg_error_t err = jpeg_dec_process(dec, &io);
        *decode_us += (uint32_t)(esp_timer_get_time() - t0);
        if (err != JPEG_ERR_OK) {
            status = DP_ST_BAD_JPEG;
            break;
        }

        int rows = io.out_size / (w * 2);
        if (rows > DP_DISPLAY_BLOCK_ROWS) {
            rows = DP_DISPLAY_BLOCK_ROWS;
        }
        if (row + rows > slot->y + h) {
            rows = slot->y + h - row;
        }
        if (rows <= 0) {
            status = DP_ST_BAD_JPEG;
            break;
        }

        const int64_t display_started = esp_timer_get_time();
        uint16_t *dst = dp_display_acquire();
        if (dst == NULL) {
            status = DP_ST_DISPLAY_ERR;
            break;
        }
        memcpy(dst, s_block, (size_t)rows * (size_t)w * 2);
        if (dp_display_submit(dst, slot->x, row, w, rows) != ESP_OK) {
            status = DP_ST_DISPLAY_ERR;
            break;
        }
        *display_submit_us += (uint32_t)(esp_timer_get_time() - display_started);
        row += rows;
    }
    if (status == DP_ST_OK && row != slot->y + h) {
        status = DP_ST_BAD_JPEG;
    }

out:
    jpeg_dec_close(dec);
    return status;
}

static void report_stats(frame_stats_t *st)
{
    const int64_t now = esp_timer_get_time();
    const int64_t span = now - st->since_us;
    if (span < STATS_PERIOD_US) {
        return;
    }
    const float secs = (float)span / 1e6f;
    const uint32_t n = st->frames ? st->frames : 1;
    taskENTER_CRITICAL(&s_stats_lock);
    const uint32_t received = s_received;
    const uint32_t rejected = s_rejected;
    s_received = 0;
    s_rejected = 0;
    taskEXIT_CRITICAL(&s_stats_lock);
    ESP_LOGI(TAG, "完整接收 %.1f fps | 显示提交 %.1f fps | JPEG %.1f KiB | 解码 %.1f ms | 刷屏提交 %.1f ms | 整帧 %.1f ms | 接收拒绝 %lu | 跳帧 %lu | 解码/刷屏错误 %lu | 内部 RAM %u KiB | PSRAM %u KiB",
             received / secs,
             st->frames / secs,
             (float)st->bytes / n / 1024.0f,
             (float)st->decode_us / n / 1000.0f,
             (float)st->display_submit_us / n / 1000.0f,
             (float)st->frame_us / n / 1000.0f,
             (unsigned long)rejected,
             (unsigned long)st->skipped,
             (unsigned long)st->errors,
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024));
    memset(st, 0, sizeof(*st));
    st->since_us = now;
}

static void decode_task(void *arg)
{
    frame_stats_t st = {.since_us = esp_timer_get_time()};
    for (;;) {
        frame_slot_t *slot = NULL;
        xQueueReceive(s_ready_q, &slot, portMAX_DELAY);

        /* 已经有更新的帧在排队时跳过旧帧，优先降低延迟。 */
        frame_slot_t *newer = NULL;
        while (xQueueReceive(s_ready_q, &newer, 0) == pdTRUE) {
            send_ack(slot->gen, slot->frame_id, DP_ST_SKIPPED, 0, 0);
            xQueueSend(s_free_q, &slot, 0);
            st.skipped++;
            slot = newer;
        }

        uint32_t decode_us = 0;
        uint32_t display_submit_us = 0;
        const int64_t t0 = esp_timer_get_time();
        const uint16_t status = decode_and_draw(slot, &decode_us, &display_submit_us);
        const uint32_t frame_us = (uint32_t)(esp_timer_get_time() - t0);
        send_ack(slot->gen, slot->frame_id, status, decode_us, frame_us);

        if (status == DP_ST_OK) {
            st.frames++;
            st.bytes += slot->len;
            st.decode_us += decode_us;
            st.display_submit_us += display_submit_us;
            st.frame_us += frame_us;
        } else {
            st.errors++;
        }
        xQueueSend(s_free_q, &slot, 0);
        report_stats(&st);
    }
}

static void count_received(bool rejected)
{
    taskENTER_CRITICAL(&s_stats_lock);
    ++s_received;
    if (rejected) {
        ++s_rejected;
    }
    taskEXIT_CRITICAL(&s_stats_lock);
}

static void handle_client(int sock, uint32_t gen)
{
    for (;;) {
        dp_frame_header_t hdr;
        if (recv_all(sock, &hdr, sizeof(hdr)) != 0) {
            ESP_LOGI(TAG, "连接结束或 %d 秒无数据 (errno=%d)", IDLE_TIMEOUT_S, errno);
            return;
        }
        if (memcmp(hdr.magic, DP_MAGIC_FRAME, 4) != 0) {
            ESP_LOGE(TAG, "帧头校验失败，断开连接");
            return;
        }
        if (hdr.jpeg_len == 0 || hdr.jpeg_len > SLOT_CAPACITY) {
            ESP_LOGW(TAG, "帧 %lu 大小 %lu 字节超出上限 %d", (unsigned long)hdr.frame_id,
                     (unsigned long)hdr.jpeg_len, SLOT_CAPACITY);
            if (drain(sock, hdr.jpeg_len) != 0) {
                return;
            }
            count_received(true);
            send_ack(gen, hdr.frame_id, DP_ST_TOO_LARGE, 0, 0);
            continue;
        }

        frame_slot_t *slot = NULL;
        if (xQueueReceive(s_free_q, &slot, pdMS_TO_TICKS(200)) != pdTRUE) {
            if (drain(sock, hdr.jpeg_len) != 0) {
                return;
            }
            count_received(true);
            send_ack(gen, hdr.frame_id, DP_ST_DROPPED, 0, 0);
            continue;
        }
        if (recv_all(sock, slot->data, hdr.jpeg_len) != 0) {
            xQueueSend(s_free_q, &slot, 0);
            return;
        }
        slot->len = hdr.jpeg_len;
        slot->frame_id = hdr.frame_id;
        slot->gen = gen;
        slot->x = hdr.x;
        slot->y = hdr.y;
        count_received(false);
        xQueueSend(s_ready_q, &slot, portMAX_DELAY);
    }
}

static void tcp_task(void *arg)
{
    int listen_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (listen_sock < 0) {
        ESP_LOGE(TAG, "创建 socket 失败");
        vTaskDelete(NULL);
        return;
    }
    int on = 1;
    setsockopt(listen_sock, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(CONFIG_DIPLAY_TCP_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(listen_sock, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(listen_sock, 1) != 0) {
        ESP_LOGE(TAG, "监听端口 %d 失败", CONFIG_DIPLAY_TCP_PORT);
        close(listen_sock);
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "等待电脑推流，TCP 端口 %d", CONFIG_DIPLAY_TCP_PORT);

    for (;;) {
        struct sockaddr_in peer;
        socklen_t peer_len = sizeof(peer);
        int sock = accept(listen_sock, (struct sockaddr *)&peer, &peer_len);
        if (sock < 0) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &on, sizeof(on));
        struct timeval tv = {.tv_sec = IDLE_TIMEOUT_S, .tv_usec = 0};
        setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        tv.tv_sec = 1;
        setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        char peer_ip[16];
        inet_ntoa_r(peer.sin_addr, peer_ip, sizeof(peer_ip));
        ESP_LOGI(TAG, "推流端已连接: %s", peer_ip);

        dp_hello_t hello;
        memcpy(hello.magic, DP_MAGIC_HELLO, 4);
        hello.width = DP_DISPLAY_WIDTH;
        hello.height = DP_DISPLAY_HEIGHT;
        hello.max_jpeg = SLOT_CAPACITY;
        hello.version = DP_PROTO_VERSION;
        hello.align = DP_JPEG_ALIGNMENT;

        xSemaphoreTake(s_sock_lock, portMAX_DELAY);
        const uint32_t gen = ++s_gen;
        s_client = sock;
        const int hello_err = send_all(sock, &hello, sizeof(hello));
        xSemaphoreGive(s_sock_lock);

        if (hello_err == 0) {
            handle_client(sock, gen);
        }

        xSemaphoreTake(s_sock_lock, portMAX_DELAY);
        s_client = -1;
        s_gen++;
        shutdown(sock, SHUT_RDWR);
        close(sock);
        xSemaphoreGive(s_sock_lock);
        ESP_LOGI(TAG, "推流端已断开: %s", peer_ip);
    }
}

/* 每秒广播一次，电脑端不填 IP 时可以自动找到圆屏。 */
static void discovery_task(void *arg)
{
    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        vTaskDelete(NULL);
        return;
    }
    int on = 1;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &on, sizeof(on));
    struct sockaddr_in to = {
        .sin_family = AF_INET,
        .sin_port = htons(CONFIG_DIPLAY_DISCOVERY_PORT),
        .sin_addr.s_addr = htonl(INADDR_BROADCAST),
    };
    char ip[16];
    char msg[128];
    for (;;) {
        if (dp_wifi_get_ip(ip, sizeof(ip))) {
            const int n = snprintf(msg, sizeof(msg), "DIPLAY1 ip=%s port=%d w=%d h=%d busy=%d",
                                   ip, CONFIG_DIPLAY_TCP_PORT, DP_DISPLAY_WIDTH, DP_DISPLAY_HEIGHT,
                                   s_client >= 0 ? 1 : 0);
            sendto(sock, msg, n, 0, (struct sockaddr *)&to, sizeof(to));
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

esp_err_t dp_frame_server_start(void)
{
    s_sock_lock = xSemaphoreCreateMutex();
    s_free_q = xQueueCreate(SLOT_COUNT, sizeof(frame_slot_t *));
    s_ready_q = xQueueCreate(SLOT_COUNT, sizeof(frame_slot_t *));
    if (!s_sock_lock || !s_free_q || !s_ready_q) {
        return ESP_ERR_NO_MEM;
    }

    /* 解码器每块输出写到内部 RAM，再拷进 DMA 缓冲，解码下一块时上一块同时在传屏。 */
    s_block = heap_caps_aligned_alloc(16, BLOCK_BYTES, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
    if (!s_block) {
        return ESP_ERR_NO_MEM;
    }
    for (int i = 0; i < SLOT_COUNT; i++) {
        s_slots[i].data = heap_caps_malloc(SLOT_CAPACITY, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s_slots[i].data) {
            return ESP_ERR_NO_MEM;
        }
        frame_slot_t *p = &s_slots[i];
        xQueueSend(s_free_q, &p, 0);
    }

    xTaskCreatePinnedToCore(decode_task, "dp_decode", 6144, NULL, 5, NULL, 1);
    xTaskCreatePinnedToCore(tcp_task, "dp_tcp", 4096, NULL, 6, NULL, 0);
    xTaskCreatePinnedToCore(discovery_task, "dp_discovery", 3072, NULL, 2, NULL, 0);
    ESP_LOGI(TAG, "接收缓冲 %d x %d KB（PSRAM），解码块 %d 字节（内部 RAM）",
             SLOT_COUNT, CONFIG_DIPLAY_MAX_JPEG_KB, BLOCK_BYTES);
    return ESP_OK;
}
