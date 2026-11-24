#pragma once

/*
 * DiPlay JPEG 推流协议 v1（TCP，所有整数为小端）
 *
 * 连接建立后：
 *   ESP32 -> 电脑  dp_hello_t   一次，告知屏幕尺寸、JPEG 上限和尺寸对齐要求
 *   电脑 -> ESP32  dp_frame_header_t + JPEG 字节，反复发送
 *   ESP32 -> 电脑  dp_ack_t     每帧一个，帧画完（最后一块已提交到屏幕）或被丢弃时回送
 *
 * 发送端用 ACK 做流控：同时在途的帧不超过 2 帧，避免延迟越积越大。
 */

#include <stdint.h>

#define DP_PROTO_VERSION 1
#define DP_JPEG_ALIGNMENT 8

#define DP_MAGIC_HELLO "DPHI"
#define DP_MAGIC_FRAME "DPJF"
#define DP_MAGIC_ACK   "DPAK"

enum {
    DP_ST_OK = 0,           /* 已解码并提交到屏幕 */
    DP_ST_BAD_JPEG = 1,     /* JPEG 无法解析或解码 */
    DP_ST_BAD_GEOMETRY = 2, /* 宽高不是 8 的倍数、起点为奇数或超出屏幕 */
    DP_ST_TOO_LARGE = 3,    /* 超过接收缓冲上限 */
    DP_ST_DROPPED = 4,      /* 接收端没有空闲缓冲 */
    DP_ST_SKIPPED = 5,      /* 已有更新的帧排队，旧帧直接跳过以降低延迟 */
    DP_ST_DISPLAY_ERR = 6,  /* 屏幕传输失败 */
};

typedef struct __attribute__((packed)) {
    char magic[4];      /* "DPHI" */
    uint16_t width;     /* 屏幕宽度，466 */
    uint16_t height;    /* 屏幕高度，466 */
    uint32_t max_jpeg;  /* 单帧 JPEG 最大字节数 */
    uint16_t version;   /* DP_PROTO_VERSION */
    uint16_t align;     /* 分块解码接受的 JPEG 宽高对齐 */
} dp_hello_t;

typedef struct __attribute__((packed)) {
    char magic[4];      /* "DPJF" */
    uint32_t frame_id;
    uint32_t jpeg_len;
    uint16_t x;         /* 在屏幕上的起点，必须为偶数 */
    uint16_t y;
} dp_frame_header_t;

typedef struct __attribute__((packed)) {
    char magic[4];      /* "DPAK" */
    uint32_t frame_id;
    uint16_t status;    /* DP_ST_* */
    uint16_t queued;    /* 回 ACK 时还在排队等待解码的帧数 */
    uint32_t decode_us; /* 纯解码耗时 */
    uint32_t frame_us;  /* 解码 + 等待屏幕缓冲 + 提交的总耗时 */
} dp_ack_t;

_Static_assert(sizeof(dp_hello_t) == 16, "dp_hello_t size");
_Static_assert(sizeof(dp_frame_header_t) == 16, "dp_frame_header_t size");
_Static_assert(sizeof(dp_ack_t) == 20, "dp_ack_t size");
