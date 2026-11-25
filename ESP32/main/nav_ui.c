#include <stdio.h>
#include <string.h>
#include <math.h>
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "lvgl.h"
#include "bsp/display.h"
#include "bsp/esp32_s3_touch_amoled_1_75.h"
#include "nav_ui.h"
#include "road_store.h"

#define MAP_DIAMETER_PX 380
#define MAP_RADIUS_PX 190.0f
#define MAP_RADIUS_M 150.0f
#define ROUTE_MAX_POINTS 500
#define MARKER_ROWS 11
#define EARTH_M 6378137.0
#define CAMERA_FRAME_MS 33
#define CAMERA_SNAP_M 150.0
#define ROAD_MARGIN_PX 128
#define ROAD_BITMAP_PX (MAP_DIAMETER_PX + 2 * ROAD_MARGIN_PX)
#define CACHE_SLICE_US 7000
#define CACHE_ROWS_PER_BATCH 64
#define CACHE_SEGMENTS_PER_BATCH 24
#define SAMPLE_SHIFT 16
#define SAMPLE_ONE (1 << SAMPLE_SHIFT)
#define DEG_TO_RAD 0.017453292519943295
#define NORTH_PIXELS_PER_E6 (DEG_TO_RAD * EARTH_M / 1000000.0 * MAP_RADIUS_PX / MAP_RADIUS_M)
#define SCREEN_BG_RGB 0x0B1926
#define MAP_BG_RGB 0xD6E8F4
#define MAP_BORDER_RGB 0x4F90A7
#define BUILDING_RGB 0x98ADC2
#define POSITION_RGB 0x123B58
#define ROUTE_RGB 0x246BCA

static const char *TAG = "RoundNavUI";

static lv_obj_t *s_map_clip;
typedef struct {
    double lat, lon;
    float east_scale;
} map_origin_t;

typedef struct {
    lv_obj_t *canvas;
    uint16_t *pixels;
    map_origin_t origin;
} map_cache_t;

static map_cache_t s_map_cache[2];
static lv_obj_t *s_map_view;
static uint16_t *s_view_pixels;
static int s_view_stride, s_cache_stride;
static uint16_t s_view_inset[MAP_DIAMETER_PX];
static uint16_t s_map_background;
static road_t *s_build_roads;
static int s_cache_front;
static bool s_cache_valid, s_cache_dirty;
static int s_build_row, s_build_road = -1, s_build_point;
static int s_view_dx, s_view_dy, s_view_heading = -1;
static int32_t s_view_progress_dm = -1;
static lv_obj_t *s_ble_label;
static lv_obj_t *s_nav_label;
static lv_obj_t *s_distance_label;
static lv_obj_t *s_nav_card;
static lv_obj_t *s_nav_arrow_lines[2];
static lv_obj_t *s_route_line;
static lv_obj_t *s_road_lines[ROAD_MAX_COUNT];
static lv_obj_t *s_marker_lines[MARKER_ROWS];
/* 160 x 32 points and the two road snapshots are allocated in PSRAM. */
static lv_point_precise_t (*s_road_screen)[ROAD_MAX_POINTS];
static lv_point_precise_t s_route_screen[ROUTE_MAX_POINTS];
static lv_point_precise_t s_marker_points[MARKER_ROWS][2];
static road_store_t *s_road_store;
static geo_point_t s_route[ROUTE_MAX_POINTS];
static uint8_t s_route_traffic[ROUTE_MAX_POINTS];
static char s_route_turns[ROUTE_MAX_POINTS];
static float s_route_meters[ROUTE_MAX_POINTS];
static int s_route_count;
static geo_point_t s_pending_route[ROUTE_MAX_POINTS];
static uint8_t s_pending_traffic[ROUTE_MAX_POINTS];
static char s_pending_turns[ROUTE_MAX_POINTS];
static int s_pending_route_expected;
static int s_pending_route_count;
/* Double preserves sub-metre movement at absolute longitude ~121 degrees. */
static double s_center_lat_e6;
static double s_center_lon_e6;
static bool s_center_valid;
static bool s_gps_valid;
static float s_heading_deg;
static int s_heading_tenths;
static float s_heading_cos = 1.0f, s_heading_sin;
static double s_camera_from_lat, s_camera_from_lon;
static double s_camera_to_lat, s_camera_to_lon;
static float s_heading_from, s_heading_delta;
static uint32_t s_camera_start, s_camera_duration, s_last_fix_tick;
static bool s_camera_animating;
static bool s_camera_refresh_ready;
static bool s_route_tracking, s_camera_follows_route;
static double s_route_progress = -1, s_progress_from, s_progress_to;
static char s_direction = 'S';
static int32_t s_distance_m = -1;
static void set_navigation_cue(char direction, int32_t meters);

/* All counters are accessed under the LVGL lock. Rendering time includes
 * waits inside LVGL, but the last asynchronous DMA can finish afterwards. */
static struct {
    int64_t window_start_us;
    int64_t render_start_us;
    int64_t wait_start_us;
    int64_t flush_start_us;
    int64_t render_total_us;
    int64_t render_max_us;
    int64_t wait_total_us;
    int64_t flush_total_us;
    int64_t camera_max_us;
    int64_t rebuild_max_us;
    uint32_t frames;
    uint32_t flushes;
    uint32_t slow_frames;
} s_display_perf;

/* Events have already left the BLE queue. Wait rather than discarding one
 * point of a road snapshot merely because a render pass owns the lock. */
static bool ui_lock(void) { return bsp_display_lock(UINT32_MAX) == ESP_OK; }
static void ui_unlock(void) { bsp_display_unlock(); }

static map_origin_t camera_origin(void)
{
    return (map_origin_t){s_center_lat_e6, s_center_lon_e6,
                          NORTH_PIXELS_PER_E6 * cos(s_center_lat_e6 * DEG_TO_RAD / 1000000.0)};
}

static void set_map_heading(float heading)
{
    s_heading_deg = heading;
    const int tenths = (int)lroundf(heading * 10.0f) % 3600;
    if (tenths == s_heading_tenths) return;
    s_heading_tenths = tenths;
    const float angle = tenths * (float)(DEG_TO_RAD / 10.0);
    s_heading_cos = cosf(angle);
    s_heading_sin = sinf(angle);
}

static void project_point(const geo_point_t *p, const map_origin_t *origin, float cs, float sn,
                          float center_px, lv_point_precise_t *out)
{
    /* Subtract in double before converting to float; preserve local detail. */
    const float x = (float)(p->lon - origin->lon) * origin->east_scale;
    const float y = (float)(origin->lat - p->lat) * (float)NORTH_PIXELS_PER_E6;
    /* A clockwise compass heading rotates the map counterclockwise. */
    out->x = (lv_coord_t)lroundf(center_px + x * cs + y * sn);
    out->y = (lv_coord_t)lroundf(center_px - x * sn + y * cs);
}

static lv_color_t road_color(int road_class)
{
    switch (road_class) {
    /* Dark, high-contrast streets on the light map, as on dash navigators. */
    case 0: return lv_color_hex(0x0E1621); /* motorway */
    case 1: return lv_color_hex(0x142030); /* trunk */
    case 2: return lv_color_hex(0x1A2736); /* primary */
    case 3: return lv_color_hex(0x212E3D); /* secondary / tertiary */
    case ROAD_CLASS_BUILDING: return lv_color_hex(BUILDING_RGB);
    case ROAD_CLASS_SERVICE: return lv_color_hex(0x4A5868); /* service, alleys */
    default: return lv_color_hex(0x2B3847); /* local streets */
    }
}

static int road_width(int road_class)
{
    /* About 1.27 px per metre at the 150 m display radius. */
    if (road_class == ROAD_CLASS_BUILDING) return 1;
    if (road_class == ROAD_CLASS_SERVICE) return 4;
    if (road_class <= 0) return 14;
    if (road_class == 1) return 13;
    if (road_class == 2) return 12;
    if (road_class == 3) return 10;
    return 7;
}

static lv_color_t route_color(uint8_t traffic)
{
    switch (traffic) {
    case 1: return lv_color_hex(0x008859);
    case 2: return lv_color_hex(0xDE7800);
    case 3: return lv_color_hex(0xCD293D);
    default: return lv_color_hex(ROUTE_RGB);
    }
}

static void fill_building(const map_cache_t *back, const road_t *road)
{
    lv_point_precise_t outline[BUILDING_MAX_POINTS];
    const int count = road->count;
    int top = ROAD_BITMAP_PX;
    int bottom = -1;
    for (int i = 0; i < count; ++i) {
        project_point(&road->points[i], &back->origin, 1, 0, ROAD_BITMAP_PX / 2, &outline[i]);
        top = LV_MIN(top, (int)outline[i].y);
        bottom = LV_MAX(bottom, (int)outline[i].y);
    }
    const uint16_t color = lv_color_to_u16(lv_color_hex(BUILDING_RGB));
    /* Even-odd spans follow concave footprints. Count each shared vertex once. */
    for (int y = LV_MAX(top, 0); y < LV_MIN(bottom, ROAD_BITMAP_PX); ++y) {
        int crossings[BUILDING_MAX_POINTS];
        int used = 0;
        for (int i = 0, previous = count - 1; i < count; previous = i++) {
            const int ay = (int)outline[previous].y;
            const int by = (int)outline[i].y;
            if ((ay > y) == (by > y)) continue;
            const int ax = (int)outline[previous].x;
            const int bx = (int)outline[i].x;
            const int x = ax + (int)((int64_t)(y - ay) * (bx - ax) / (by - ay));
            int position = used++;
            while (position > 0 && crossings[position - 1] > x) {
                crossings[position] = crossings[position - 1];
                --position;
            }
            crossings[position] = x;
        }
        uint16_t *row = back->pixels + y * s_cache_stride;
        for (int i = 1; i < used; i += 2) {
            const int end = LV_MIN(crossings[i], ROAD_BITMAP_PX);
            for (int x = LV_MAX(crossings[i - 1], 0); x < end; ++x) row[x] = color;
        }
    }
}

static void start_cache_build(map_cache_t *back)
{
    back->origin = camera_origin();
    memcpy(s_build_roads, s_road_store->roads, sizeof(s_road_store->roads));
    for (int x = 0; x < ROAD_BITMAP_PX; ++x) back->pixels[x] = s_map_background;
    s_build_row = 1;
    s_build_road = 0;
    s_build_point = 1;
    s_cache_dirty = false;
}

static void draw_cache_batch(const map_cache_t *back)
{
    if (s_build_row < ROAD_BITMAP_PX) {
        const int limit = LV_MIN(s_build_row + CACHE_ROWS_PER_BATCH, ROAD_BITMAP_PX);
        for (; s_build_row < limit; ++s_build_row) {
            memcpy(back->pixels + s_build_row * s_cache_stride, back->pixels, ROAD_BITMAP_PX * sizeof(uint16_t));
        }
        return;
    }

    lv_layer_t layer;
    lv_canvas_init_layer(back->canvas, &layer);
    int budget = CACHE_SEGMENTS_PER_BATCH;
    while (budget > 0 && s_build_road < ROAD_MAX_COUNT) {
        const road_t *road = &s_build_roads[s_build_road];
        const geo_point_t *points = road->points;
        const int count = road->active ? road->count : 0;
        if (s_build_point >= count) {
            ++s_build_road;
            s_build_point = 1;
            continue;
        }
        if (road->road_class == ROAD_CLASS_BUILDING) {
            fill_building(back, road);
            ++s_build_road;
            s_build_point = 1;
            budget -= count;
            continue;
        }
        lv_draw_line_dsc_t line;
        lv_draw_line_dsc_init(&line);
        line.color = road_color(road->road_class);
        line.width = road_width(road->road_class);
        line.round_start = line.round_end = true;
        project_point(&points[s_build_point - 1], &back->origin, 1, 0, ROAD_BITMAP_PX / 2, &line.p1);
        while (budget > 0 && s_build_point < count) {
            project_point(&points[s_build_point++], &back->origin, 1, 0, ROAD_BITMAP_PX / 2, &line.p2);
            lv_draw_line(&layer, &line);
            line.p1 = line.p2;
            --budget;
        }
    }
    lv_canvas_finish_layer(back->canvas, &layer);
}

static bool clip_route_axis(int start, int delta, double *first, double *last)
{
    if (!delta) return start >= -3 && start < MAP_DIAMETER_PX + 3;
    const double a = (-3.0 - start) / delta;
    const double b = (MAP_DIAMETER_PX + 2.0 - start) / delta;
    *first = fmax(*first, fmin(a, b));
    *last = fmin(*last, fmax(a, b));
    return *first <= *last;
}

static void draw_route_segment(int x, int y, int end_x, int end_y, uint16_t color)
{
    const int dx = end_x - x, dy = end_y - y;
    double first = 0, last = 1;
    if (!clip_route_axis(x, dx, &first, &last) || !clip_route_axis(y, dy, &first, &last)) return;
    int px = x + (int)lround(dx * first), py = y + (int)lround(dy * first);
    const int last_x = x + (int)lround(dx * last), last_y = y + (int)lround(dy * last);
    const int width = abs(last_x - px), height = abs(last_y - py);
    const int step_x = px < last_x ? 1 : -1, step_y = py < last_y ? 1 : -1;
    int error = width - height;
    while (true) {
        for (int row = -2; row <= 2; ++row) {
            const int sy = py + row;
            if ((unsigned)sy >= MAP_DIAMETER_PX) continue;
            const int half_width = abs(row) == 2 ? 2 : 3;
            const int left = LV_MAX(px - half_width, s_view_inset[sy]);
            const int right = LV_MIN(px + half_width, MAP_DIAMETER_PX - s_view_inset[sy] - 1);
            for (int sx = left; sx <= right; ++sx) s_view_pixels[sy * s_view_stride + sx] = color;
        }
        if (px == last_x && py == last_y) break;
        const int doubled = 2 * error;
        if (doubled > -height) { error -= height; px += step_x; }
        if (doubled < width) { error += width; py += step_y; }
    }
}

static void draw_route_overlay(void)
{
    if (s_route_count < 2) return;
    const map_origin_t origin = camera_origin();
    lv_point_precise_t a, b;
    project_point(&s_route[0], &origin, s_heading_cos, s_heading_sin, MAP_RADIUS_PX, &a);
    for (int i = 1; i < s_route_count; ++i, a = b) {
        project_point(&s_route[i], &origin, s_heading_cos, s_heading_sin, MAP_RADIUS_PX, &b);
        const float start = s_route_meters[i - 1], end = s_route_meters[i];
        const uint16_t neutral = lv_color_to_u16(lv_color_hex(ROUTE_RGB));
        const uint16_t traffic = lv_color_to_u16(route_color(s_route_traffic[i - 1]));
        if (s_route_progress >= end) {
            draw_route_segment(a.x, a.y, b.x, b.y, neutral);
        } else if (s_route_progress <= start || end <= start) {
            draw_route_segment(a.x, a.y, b.x, b.y, traffic);
        } else {
            const double t = (s_route_progress - start) / (end - start);
            const int x = (int)lround(a.x + (b.x - a.x) * t);
            const int y = (int)lround(a.y + (b.y - a.y) * t);
            draw_route_segment(a.x, a.y, x, y, neutral);
            draw_route_segment(x, y, b.x, b.y, traffic);
        }
    }
}

static void compose_map_view(const map_cache_t *front, int dx, int dy)
{
    const int32_t progress_dm = (int32_t)lround(s_route_progress * 10);
    if (dx == s_view_dx && dy == s_view_dy && s_heading_tenths == s_view_heading &&
        progress_dm == s_view_progress_dm) return;
    s_view_dx = dx;
    s_view_dy = dy;
    s_view_heading = s_heading_tenths;
    s_view_progress_dm = progress_dm;

    const int radius = MAP_DIAMETER_PX / 2;
    const int cx = ROAD_BITMAP_PX / 2 - dx;
    const int cy = ROAD_BITMAP_PX / 2 - dy;
    const int cs = (int)lroundf(s_heading_cos * SAMPLE_ONE);
    const int sn = (int)lroundf(s_heading_sin * SAMPLE_ONE);
    const bool covered = abs(dx) <= ROAD_MARGIN_PX && abs(dy) <= ROAD_MARGIN_PX;
    const bool intersects = abs(dx) < ROAD_BITMAP_PX / 2 + radius && abs(dy) < ROAD_BITMAP_PX / 2 + radius;
    /* Inverse sample only the circular viewport in native RGB565. The
     * border and corners stay opaque, so LVGL only needs a plain image blit. */
    for (int row = 0; row < MAP_DIAMETER_PX; ++row) {
        const int inset = s_view_inset[row];
        const int count = MAP_DIAMETER_PX - 2 * inset;
        uint16_t *dest = s_view_pixels + row * s_view_stride + inset;
        const int x = inset - radius;
        const int y = row - radius;
        if (covered && s_heading_tenths == 0) {
            memcpy(dest, front->pixels + (cy + y) * s_cache_stride + cx + x, count * sizeof(*dest));
            continue;
        }
        /* Skip sampling a stale cache after a long GPS jump. */
        if (!intersects) {
            for (int i = 0; i < count; ++i) dest[i] = s_map_background;
            continue;
        }
        int sx = cx * SAMPLE_ONE + SAMPLE_ONE / 2 + x * cs - y * sn;
        int sy = cy * SAMPLE_ONE + SAMPLE_ONE / 2 + x * sn + y * cs;
        for (int i = 0; i < count; ++i) {
            const int px = sx >> SAMPLE_SHIFT;
            const int py = sy >> SAMPLE_SHIFT;
            dest[i] = (covered || ((unsigned)px < ROAD_BITMAP_PX && (unsigned)py < ROAD_BITMAP_PX))
                ? front->pixels[py * s_cache_stride + px] : s_map_background;
            sx += cs;
            sy += sn;
        }
    }
    draw_route_overlay();
    lv_obj_invalidate(s_map_view);
}

static void refresh_cached_map(void)
{
    if (!s_map_view || !s_center_valid || !s_road_store) return;
    map_cache_t *front = &s_map_cache[s_cache_front];
    int dx = (int)lround((front->origin.lon - s_center_lon_e6) * front->origin.east_scale);
    int dy = (int)lround((s_center_lat_e6 - front->origin.lat) * NORTH_PIXELS_PER_E6);
    const int64_t started = esp_timer_get_time();
    map_cache_t *back = &s_map_cache[1 - s_cache_front];
    if (s_cache_dirty || (s_build_road < 0 && (!s_cache_valid || abs(dx) > ROAD_MARGIN_PX || abs(dy) > ROAD_MARGIN_PX))) {
        start_cache_build(back);
    }
    if (s_build_road >= 0) {
        /* A completed snapshot can replace the inputs between refreshes.
         * Restart its back buffer, while the front buffer keeps moving. */
        do {
            draw_cache_batch(back);
        } while (s_build_road < ROAD_MAX_COUNT && esp_timer_get_time() - started < CACHE_SLICE_US);
        if (s_build_road == ROAD_MAX_COUNT) {
            s_build_road = -1;
            s_cache_front = 1 - s_cache_front;
            s_cache_valid = true;
            s_view_heading = -1;
            front = back;
            dx = (int)lround((front->origin.lon - s_center_lon_e6) * front->origin.east_scale);
            dy = (int)lround((s_center_lat_e6 - front->origin.lat) * NORTH_PIXELS_PER_E6);
        }
        const int64_t rebuild_us = esp_timer_get_time() - started;
        if (rebuild_us > s_display_perf.rebuild_max_us) s_display_perf.rebuild_max_us = rebuild_us;
    }
    if (s_cache_valid) compose_map_view(front, dx, dy);
}

static void render_roads(bool refresh_style)
{
    if (s_map_view || !s_map_clip || !s_center_valid || !s_road_store || !s_road_screen) return;
    const map_origin_t origin = camera_origin();
    for (int r = 0; r < ROAD_MAX_COUNT; ++r) {
        road_t *road = &s_road_store->roads[r];
        if (!road->active || road->count < 2) {
            if (s_road_lines[r]) lv_obj_add_flag(s_road_lines[r], LV_OBJ_FLAG_HIDDEN);
            continue;
        }
        for (int i = 0; i < road->count; ++i) {
            project_point(&road->points[i], &origin, s_heading_cos, s_heading_sin,
                          MAP_RADIUS_PX, &s_road_screen[r][i]);
        }

        if (s_road_lines[r] == NULL) {
            s_road_lines[r] = lv_line_create(s_map_clip);
            lv_obj_set_size(s_road_lines[r], MAP_DIAMETER_PX, MAP_DIAMETER_PX);
            lv_obj_center(s_road_lines[r]);
            lv_obj_clear_flag(s_road_lines[r], LV_OBJ_FLAG_CLICKABLE);
        }
        lv_obj_remove_flag(s_road_lines[r], LV_OBJ_FLAG_HIDDEN);
        lv_line_set_points(s_road_lines[r], s_road_screen[r], road->count);
        if (refresh_style) {
            lv_obj_set_style_line_color(s_road_lines[r], road_color(road->road_class), 0);
            lv_obj_set_style_line_opa(s_road_lines[r], LV_OPA_COVER, 0);
            lv_obj_set_style_line_width(s_road_lines[r], road_width(road->road_class), 0);
            lv_obj_set_style_line_rounded(s_road_lines[r], true, 0);
        }
    }
}

static void render_route(void)
{
    if (s_map_view) return;
    if (s_route_count < 2 || !s_map_clip || !s_center_valid) {
        if (s_route_line) lv_obj_add_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
        return;
    }
    const map_origin_t origin = camera_origin();
    for (int i = 0; i < s_route_count; ++i) {
        project_point(&s_route[i], &origin, s_heading_cos, s_heading_sin,
                      MAP_RADIUS_PX, &s_route_screen[i]);
    }
    if (s_route_line == NULL) {
        s_route_line = lv_line_create(s_map_clip);
        lv_obj_set_size(s_route_line, MAP_DIAMETER_PX, MAP_DIAMETER_PX);
        lv_obj_center(s_route_line);
        lv_obj_set_style_line_color(s_route_line, lv_color_hex(ROUTE_RGB), 0);
        lv_obj_set_style_line_width(s_route_line, 6, 0);
        lv_obj_set_style_line_rounded(s_route_line, true, 0);
        lv_obj_clear_flag(s_route_line, LV_OBJ_FLAG_CLICKABLE);
    }
    lv_obj_remove_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
    lv_line_set_points(s_route_line, s_route_screen, s_route_count);
}

static void create_position_marker(void)
{
    for (int i = 0; i < MARKER_ROWS; ++i) {
        s_marker_lines[i] = lv_line_create(s_map_clip);
        lv_obj_set_size(s_marker_lines[i], MAP_DIAMETER_PX, MAP_DIAMETER_PX);
        lv_obj_center(s_marker_lines[i]);
        lv_obj_set_style_line_color(s_marker_lines[i], lv_color_hex(POSITION_RGB), 0);
        lv_obj_set_style_line_width(s_marker_lines[i], 2, 0);
        lv_obj_set_style_line_rounded(s_marker_lines[i], true, 0);
        lv_obj_clear_flag(s_marker_lines[i], LV_OBJ_FLAG_CLICKABLE);
        const int y = (int)MAP_RADIUS_PX - 10 + 2 * i;
        s_marker_points[i][0] = (lv_point_precise_t){(int)MAP_RADIUS_PX - i, y};
        s_marker_points[i][1] = (lv_point_precise_t){(int)MAP_RADIUS_PX + i, y};
        lv_line_set_points(s_marker_lines[i], s_marker_points[i], 2);
    }
}

static void raise_map_overlays(void)
{
    if (s_route_line) lv_obj_move_foreground(s_route_line);
    for (int i = 0; i < MARKER_ROWS; ++i) lv_obj_move_foreground(s_marker_lines[i]);
}

static void wake_display(void)
{
    lv_timer_t *refresh = lv_display_get_refr_timer(lv_display_get_default());
    if (refresh) {
        lv_timer_resume(refresh);
        lv_timer_ready(refresh);
    }
}

/* Called with the LVGL lock held (timer callbacks already hold that lock). */
static void apply_route_progress(double meters)
{
    const int last = s_route_count - 1;
    const double total = s_route_meters[last];
    /* Android sends decimetres while the display measures the encoded vertices. */
    s_route_progress = meters + 0.15 >= total ? total : meters;
    int end = 1;
    while (end < last && s_route_meters[end] <= s_route_progress) ++end;
    const double length = s_route_meters[end] - s_route_meters[end - 1];
    const double fraction = length > 0 ? (s_route_progress - s_route_meters[end - 1]) / length : 1;
    const geo_point_t *a = &s_route[end - 1], *b = &s_route[end];
    s_center_lat_e6 = a->lat + (b->lat - a->lat) * fraction;
    s_center_lon_e6 = a->lon + (b->lon - a->lon) * fraction;

    while (end < last && !s_route_turns[end]) ++end;
    const int32_t remaining = (int32_t)ceil(s_route_meters[end] - s_route_progress);
    set_navigation_cue(end < last && remaining <= 500 ? s_route_turns[end] : 'S', remaining);
}

static void advance_camera(uint32_t now)
{
    if (!s_camera_animating) return;
    const uint32_t elapsed = now - s_camera_start;
    const double t = elapsed >= s_camera_duration ? 1.0 : (double)elapsed / s_camera_duration;
    s_center_lat_e6 = s_camera_from_lat + (s_camera_to_lat - s_camera_from_lat) * t;
    s_center_lon_e6 = s_camera_from_lon + (s_camera_to_lon - s_camera_from_lon) * t;
    if (s_camera_follows_route) apply_route_progress(s_progress_from + (s_progress_to - s_progress_from) * t);
    set_map_heading(fmodf(s_heading_from + s_heading_delta * (float)t + 360.0f, 360.0f));
    render_roads(false);
    render_route();
    if (t >= 1.0) s_camera_animating = false;
}

static void display_event(lv_event_t *event)
{
    const int64_t now_us = esp_timer_get_time();
    switch (lv_event_get_code(event)) {
    case LV_EVENT_REFR_START: {
        /* Update immediately before LVGL examines invalid areas. One clock
         * drives both motion and drawing, avoiding independent timer phases. */
        advance_camera(lv_tick_get());
        refresh_cached_map();
        const int64_t duration_us = esp_timer_get_time() - now_us;
        if (duration_us > s_display_perf.camera_max_us) s_display_perf.camera_max_us = duration_us;
        if (now_us - s_display_perf.window_start_us >= 5000000) {
            const double seconds = (now_us - s_display_perf.window_start_us) / 1000000.0;
            const double frames = s_display_perf.frames ? s_display_perf.frames : 1;
            ESP_LOGI(TAG, "DISPLAY PERF lvgl_fps=%.1f render_ms=%.1f max_ms=%.1f wait_ms=%.1f submit_ms=%.1f "
                     "flushes_per_frame=%.1f slow=%lu/%lu camera_max_ms=%.1f rebuild_max_ms=%.1f",
                     s_display_perf.frames / seconds,
                     s_display_perf.render_total_us / frames / 1000.0,
                     s_display_perf.render_max_us / 1000.0,
                     s_display_perf.wait_total_us / frames / 1000.0,
                     s_display_perf.flush_total_us / frames / 1000.0,
                     s_display_perf.flushes / frames,
                     (unsigned long)s_display_perf.slow_frames, (unsigned long)s_display_perf.frames,
                     s_display_perf.camera_max_us / 1000.0, s_display_perf.rebuild_max_us / 1000.0);
            memset(&s_display_perf, 0, sizeof(s_display_perf));
            s_display_perf.window_start_us = esp_timer_get_time();
        }
        break;
    }
    case LV_EVENT_RENDER_START:
        s_display_perf.render_start_us = now_us;
        break;
    case LV_EVENT_REFR_READY:
        /* LVGL pauses its refresh timer at entry. Continue polling motion
         * even when subpixel progress has not invalidated any pixels yet. */
        if (s_camera_animating || s_build_road >= 0 || (s_cache_dirty && s_center_valid)) {
            lv_timer_t *refresh = lv_display_get_refr_timer(lv_display_get_default());
            if (refresh) lv_timer_resume(refresh);
        }
        break;
    case LV_EVENT_RENDER_READY: {
        const int64_t duration_us = now_us - s_display_perf.render_start_us;
        s_display_perf.render_total_us += duration_us;
        if (duration_us > s_display_perf.render_max_us) s_display_perf.render_max_us = duration_us;
        if (duration_us > CAMERA_FRAME_MS * 1000) s_display_perf.slow_frames++;
        s_display_perf.frames++;
        break;
    }
    case LV_EVENT_FLUSH_START:
        s_display_perf.flushes++;
        s_display_perf.flush_start_us = now_us;
        break;
    case LV_EVENT_FLUSH_FINISH:
        s_display_perf.flush_total_us += now_us - s_display_perf.flush_start_us;
        break;
    case LV_EVENT_FLUSH_WAIT_START:
        s_display_perf.wait_start_us = now_us;
        break;
    case LV_EVENT_FLUSH_WAIT_FINISH:
        s_display_perf.wait_total_us += now_us - s_display_perf.wait_start_us;
        break;
    default:
        break;
    }
}

static void draw_nav_arrow(void)
{
    static const lv_point_precise_t straight[2][3] = {
        {{27, 37}, {27, 8}}, {{16, 20}, {27, 8}, {38, 20}}
    };
    static const lv_point_precise_t left[2][3] = {
        {{27, 37}, {27, 17}, {8, 17}}, {{18, 7}, {8, 17}, {18, 27}}
    };
    static const lv_point_precise_t right[2][3] = {
        {{27, 37}, {27, 17}, {46, 17}}, {{36, 7}, {46, 17}, {36, 27}}
    };
    static const lv_point_precise_t uturn[2][3] = {
        {{27, 8}, {27, 37}}, {{16, 26}, {27, 37}, {38, 26}}
    };
    const lv_point_precise_t (*src)[3] = straight;
    if (s_direction == 'L') src = left;
    else if (s_direction == 'R') src = right;
    else if (s_direction == 'U') src = uturn;
    const uint32_t shaft_count = (s_direction == 'L' || s_direction == 'R') ? 3 : 2;
    const bool hide_arrow = s_direction == 'S' && s_distance_m <= 0;
    for (int i = 0; i < 2; ++i) {
        if (s_nav_arrow_lines[i] == NULL) {
            s_nav_arrow_lines[i] = lv_line_create(s_nav_card);
            lv_obj_align(s_nav_arrow_lines[i], LV_ALIGN_LEFT_MID, 8, 0);
            lv_obj_set_size(s_nav_arrow_lines[i], 56, 45);
            lv_obj_set_style_line_color(s_nav_arrow_lines[i], lv_color_hex(0x87E1D0), 0);
            lv_obj_set_style_line_width(s_nav_arrow_lines[i], 6, 0);
            lv_obj_set_style_line_rounded(s_nav_arrow_lines[i], true, 0);
            lv_obj_set_clickable(s_nav_arrow_lines[i], false);
        }
        lv_obj_set_hidden(s_nav_arrow_lines[i], hide_arrow);
        if (hide_arrow) continue;
        lv_line_set_points(s_nav_arrow_lines[i], src[i], i == 0 ? shaft_count : 3);
        lv_obj_move_foreground(s_nav_arrow_lines[i]);
    }
}

static void update_nav_label(void)
{
    if (s_nav_label == NULL || s_distance_label == NULL) return;
    const char *word = "STRAIGHT";
    if (s_direction == 'L') word = "TURN LEFT";
    else if (s_direction == 'R') word = "TURN RIGHT";
    else if (s_direction == 'U') word = "U-TURN";
    else if (s_distance_m == 0) word = "ARRIVED";
    else if (s_distance_m < 0) word = "";
    if (strcmp(lv_label_get_text(s_nav_label), word) != 0) {
        lv_obj_set_hidden(s_nav_card, word[0] == '\0');
        lv_label_set_text(s_nav_label, word);
        draw_nav_arrow();
    }
    char d[32];
    if (s_direction == 'S' && s_distance_m <= 0) d[0] = '\0';
    else if (s_distance_m < 0) snprintf(d, sizeof(d), "-- m");
    else if (s_distance_m >= 1000) snprintf(d, sizeof(d), "%.1f km", (double)s_distance_m / 1000.0);
    else snprintf(d, sizeof(d), "%ld m", (long)s_distance_m);
    if (strcmp(lv_label_get_text(s_distance_label), d) != 0) lv_label_set_text(s_distance_label, d);
}

static void create_map_surround(lv_obj_t *screen)
{
    const size_t stride = lv_draw_buf_width_to_stride(MAP_DIAMETER_PX, LV_COLOR_FORMAT_ARGB8888);
    uint8_t *pixels = heap_caps_aligned_alloc(LV_DRAW_BUF_ALIGN, stride * MAP_DIAMETER_PX,
                                            MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!pixels) {
        ESP_LOGW(TAG, "Map surround allocation failed; using circular clip");
        return;
    }
    /* An opaque surround with a transparent centre keeps the circle without
     * asking LVGL to render the scrolling bitmap into a clipped layer. */
    for (int y = 0; y < MAP_DIAMETER_PX; ++y) {
        uint32_t *row = (uint32_t *)(pixels + y * stride);
        for (int x = 0; x < MAP_DIAMETER_PX; ++x) {
            const float dx = x + 0.5f - MAP_RADIUS_PX;
            const float dy = y + 0.5f - MAP_RADIUS_PX;
            const float distance = sqrtf(dx * dx + dy * dy);
            if (distance >= MAP_RADIUS_PX) row[x] = 0xFF000000 | SCREEN_BG_RGB;
            else if (distance >= MAP_RADIUS_PX - 2.0f) row[x] = 0xFF000000 | MAP_BORDER_RGB;
            else row[x] = 0;
        }
    }
    lv_obj_t *surround = lv_canvas_create(screen);
    if (!surround) {
        heap_caps_free(pixels);
        return;
    }
    lv_canvas_set_buffer(surround, pixels, MAP_DIAMETER_PX, MAP_DIAMETER_PX, LV_COLOR_FORMAT_ARGB8888);
    lv_obj_align(surround, LV_ALIGN_CENTER, 0, 0);
    lv_obj_remove_flag(surround, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_clip_corner(s_map_clip, false, 0);
    ESP_LOGI(TAG, "Map clipping: static surround");
}

static lv_obj_t *create_rgb565_canvas(int size, int stride, uint16_t **pixels)
{
    *pixels = heap_caps_aligned_alloc(LV_DRAW_BUF_ALIGN, (size_t)stride * size * sizeof(**pixels),
                                      MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!*pixels) return NULL;
    lv_obj_t *canvas = lv_canvas_create(s_map_clip);
    if (!canvas) {
        heap_caps_free(*pixels);
        *pixels = NULL;
        return NULL;
    }
    lv_canvas_set_buffer(canvas, *pixels, size, size, LV_COLOR_FORMAT_RGB565);
    lv_obj_set_clickable(canvas, false);
    return canvas;
}

static bool init_cached_map(void)
{
    s_cache_stride = lv_draw_buf_width_to_stride(ROAD_BITMAP_PX, LV_COLOR_FORMAT_RGB565) / sizeof(uint16_t);
    s_view_stride = lv_draw_buf_width_to_stride(MAP_DIAMETER_PX, LV_COLOR_FORMAT_RGB565) / sizeof(uint16_t);
    s_build_roads = heap_caps_aligned_alloc(LV_DRAW_BUF_ALIGN, sizeof(s_road_store->roads),
                                           MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!s_build_roads) return false;
    for (int i = 0; i < 2; ++i) {
        map_cache_t *cache = &s_map_cache[i];
        cache->canvas = create_rgb565_canvas(ROAD_BITMAP_PX, s_cache_stride, &cache->pixels);
        if (cache->canvas) lv_obj_set_hidden(cache->canvas, true);
    }
    if (s_map_cache[0].canvas && s_map_cache[1].canvas) {
        s_map_view = create_rgb565_canvas(MAP_DIAMETER_PX, s_view_stride, &s_view_pixels);
    }
    if (!s_map_view) {
        for (int i = 0; i < 2; ++i) {
            map_cache_t *cache = &s_map_cache[i];
            if (cache->canvas) lv_obj_delete(cache->canvas);
            heap_caps_free(cache->pixels);
        }
        heap_caps_free(s_build_roads);
        return false;
    }

    s_map_background = lv_color_to_u16(lv_color_hex(MAP_BG_RGB));
    const uint16_t outside = lv_color_to_u16(lv_color_hex(SCREEN_BG_RGB));
    const uint16_t border = lv_color_to_u16(lv_color_hex(MAP_BORDER_RGB));
    const float inner_radius = MAP_RADIUS_PX - 2.0f;
    for (int y = 0; y < MAP_DIAMETER_PX; ++y) {
        s_view_inset[y] = MAP_DIAMETER_PX / 2;
        const float dy = y + 0.5f - MAP_RADIUS_PX;
        for (int x = 0; x < MAP_DIAMETER_PX; ++x) {
            const float dx = x + 0.5f - MAP_RADIUS_PX;
            const float distance_sq = dx * dx + dy * dy;
            uint16_t color = outside;
            if (distance_sq < inner_radius * inner_radius) {
                color = s_map_background;
                if (x < s_view_inset[y]) s_view_inset[y] = x;
            } else if (distance_sq < MAP_RADIUS_PX * MAP_RADIUS_PX) {
                color = border;
            }
            s_view_pixels[y * s_view_stride + x] = color;
        }
    }
    lv_obj_center(s_map_view);
    lv_obj_set_style_clip_corner(s_map_clip, false, 0);
    return true;
}

void nav_ui_init(void)
{
    if (!ui_lock()) { ESP_LOGE(TAG, "Could not lock LVGL during UI init"); return; }
    s_road_store = heap_caps_calloc(1, sizeof(*s_road_store), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!s_road_store) s_road_store = heap_caps_calloc(1, sizeof(*s_road_store), MALLOC_CAP_8BIT);
    s_road_screen = heap_caps_calloc(ROAD_MAX_COUNT, sizeof(*s_road_screen), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!s_road_store) ESP_LOGE(TAG, "Road store allocation failed; nearby map disabled");
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(SCREEN_BG_RGB), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    s_map_clip = lv_obj_create(screen);
    lv_obj_set_size(s_map_clip, MAP_DIAMETER_PX, MAP_DIAMETER_PX);
    lv_obj_align(s_map_clip, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_radius(s_map_clip, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_clip_corner(s_map_clip, true, 0);
    lv_obj_set_style_bg_color(s_map_clip, lv_color_hex(MAP_BG_RGB), 0);
    lv_obj_set_style_bg_opa(s_map_clip, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(s_map_clip, lv_color_hex(MAP_BORDER_RGB), 0);
    lv_obj_set_style_border_width(s_map_clip, 2, 0);
    lv_obj_set_style_pad_all(s_map_clip, 0, 0);
    lv_obj_clear_flag(s_map_clip, LV_OBJ_FLAG_SCROLLABLE);

    lv_display_t *display = lv_display_get_default();
    lv_timer_t *refresh = lv_display_get_refr_timer(display);
    if (refresh) lv_timer_set_period(refresh, CAMERA_FRAME_MS);
    s_camera_refresh_ready = refresh && lv_display_add_event_cb(display, display_event, LV_EVENT_ALL, NULL);
    if (!s_camera_refresh_ready || !init_cached_map()) create_map_surround(screen);
    ESP_LOGI(TAG, "Road renderer: %s", s_map_view ? "RGB565 cache" : "vector fallback");

    s_nav_card = lv_obj_create(screen);
    lv_obj_set_size(s_nav_card, 174, 61);
    lv_obj_align(s_nav_card, LV_ALIGN_TOP_MID, 0, 37);
    lv_obj_set_style_radius(s_nav_card, 16, 0);
    lv_obj_set_style_bg_color(s_nav_card, lv_color_hex(SCREEN_BG_RGB), 0);
    lv_obj_set_style_border_color(s_nav_card, lv_color_hex(MAP_BORDER_RGB), 0);
    lv_obj_set_style_border_width(s_nav_card, 1, 0);
    lv_obj_set_style_pad_all(s_nav_card, 0, 0);
    lv_obj_set_scrollable(s_nav_card, false);
    lv_obj_set_clickable(s_nav_card, false);
    lv_obj_set_hidden(s_nav_card, true);

    s_nav_label = lv_label_create(s_nav_card);
    lv_label_set_text(s_nav_label, "");
    lv_obj_set_style_text_color(s_nav_label, lv_color_white(), 0);
    lv_obj_set_style_text_font(s_nav_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_nav_label, LV_ALIGN_TOP_LEFT, 69, 8);

    s_distance_label = lv_label_create(s_nav_card);
    lv_label_set_text(s_distance_label, "");
    lv_obj_set_style_text_color(s_distance_label, lv_color_hex(0xD4DCE5), 0);
#if LV_FONT_MONTSERRAT_24
    lv_obj_set_style_text_font(s_distance_label, &lv_font_montserrat_24, 0);
#endif
    lv_obj_align(s_distance_label, LV_ALIGN_BOTTOM_LEFT, 69, -6);

    s_ble_label = lv_label_create(screen);
    lv_label_set_text(s_ble_label, "BLE WAIT");
    lv_obj_set_style_text_color(s_ble_label, lv_color_hex(0x788797), 0);
    lv_obj_set_style_text_font(s_ble_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_ble_label, LV_ALIGN_BOTTOM_MID, 0, -12);

    s_route_count = 0;
    draw_nav_arrow();
    create_position_marker();
    s_display_perf.window_start_us = esp_timer_get_time();
    ESP_LOGI(TAG, "Display motion on LVGL refresh, period=%d ms, metrics=%s",
             CAMERA_FRAME_MS, s_camera_refresh_ready ? "enabled" : "unavailable");
    ui_unlock();
}

void nav_ui_set_ble_state(bool connected)
{
    if (!ui_lock()) return;
    if (!connected) {
        road_store_abort(s_road_store);
        s_pending_route_expected = 0;
        s_camera_animating = false;
        s_gps_valid = false; /* First fix after reconnect snaps to its actual location. */
        s_route_tracking = false;
        s_camera_follows_route = false;
        s_direction = 'S';
        s_distance_m = -1;
        update_nav_label();
    }
    if (s_ble_label) {
        lv_label_set_text(s_ble_label, connected ? "BLE CONNECTED" : "BLE WAIT");
        lv_obj_set_style_text_color(s_ble_label, connected ? lv_color_hex(0x4DD69A) : lv_color_hex(0x788797), 0);
    }
    ui_unlock();
}

static void set_navigation_cue(char direction, int32_t meters)
{
    if (s_direction == direction && s_distance_m == meters) return;
    const bool new_direction = s_direction != direction;
    s_direction = direction;
    s_distance_m = meters;
    update_nav_label();
    if (s_direction == 'S' && s_distance_m == 0) {
        ESP_LOGI(TAG, "Navigation cue applied: arrival");
    } else if (new_direction) {
        ESP_LOGI(TAG, "Navigation cue applied: %c at %ld m", s_direction, (long)s_distance_m);
    }
}

void nav_ui_apply_nav(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    if (!s_route_tracking) set_navigation_cue(event->direction, event->distance_m);
    ui_unlock();
}

void nav_ui_apply_map_event(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    if (event->type == ROUNDNAV_EVENT_MAP_BEGIN) {
        s_pending_route_expected = event->count >= 2 && event->count <= ROUTE_MAX_POINTS ? event->count : 0;
        s_pending_route_count = 0;
    } else if (event->type == ROUNDNAV_EVENT_MAP_POINT) {
        const int index = event->index;
        if (event->traffic <= 3 && index >= 0 && index < s_pending_route_expected && index == s_pending_route_count) {
            s_pending_route[index] = (geo_point_t){ .lat = event->lat_e6, .lon = event->lon_e6 };
            s_pending_traffic[index] = event->traffic;
            s_pending_turns[index] = event->route_turn;
            ++s_pending_route_count;
        } else if (!(index >= 0 && index < s_pending_route_expected && index < s_pending_route_count &&
                     s_pending_route[index].lat == event->lat_e6 && s_pending_route[index].lon == event->lon_e6 &&
                     s_pending_traffic[index] == event->traffic && s_pending_turns[index] == event->route_turn)) {
            /* A retried ATT packet can repeat points already queued by its first write. */
            s_pending_route_expected = 0;
        }
    } else if (event->type == ROUNDNAV_EVENT_MAP_END) {
        if (s_pending_route_expected > 0 && s_pending_route_count == s_pending_route_expected) {
            s_route_count = s_pending_route_count;
            memcpy(s_route, s_pending_route, s_route_count * sizeof(*s_route));
            memcpy(s_route_traffic, s_pending_traffic, s_route_count);
            memcpy(s_route_turns, s_pending_turns, s_route_count);
            s_route_meters[0] = 0;
            for (int i = 1; i < s_route_count; ++i) {
                const geo_point_t *a = &s_route[i - 1], *b = &s_route[i];
                const double east = (b->lon - a->lon) * cos((a->lat + b->lat) * DEG_TO_RAD / 2000000.0);
                s_route_meters[i] = s_route_meters[i - 1] + hypot(east, b->lat - a->lat) * (EARTH_M * DEG_TO_RAD / 1000000.0);
            }
            s_route_turns[s_route_count - 1] = 0;
            if (s_route_tracking) set_navigation_cue('S', -1);
            s_route_progress = -1;
            s_route_tracking = false;
            s_camera_follows_route = false;
            if (s_map_view) {
                s_view_heading = -1;
                wake_display();
            } else {
                render_route();
                raise_map_overlays();
            }
            int traffic[4] = {0};
            for (int i = 0; i < s_route_count - 1; ++i) ++traffic[s_route_traffic[i]];
            ESP_LOGI(TAG, "Route committed: points=%d traffic clear=%d slow=%d busy=%d unknown=%d",
                     s_route_count, traffic[1], traffic[2], traffic[3], traffic[0]);
        } else {
            ESP_LOGW(TAG, "Incomplete/invalid route; keeping previous route");
        }
        s_pending_route_expected = 0;
    }
    ui_unlock();
}

void nav_ui_apply_road_event(const roundnav_event_t *event)
{
    if (event == NULL || !s_road_store || !ui_lock()) return;
    if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN) {
        ESP_LOGI(TAG, "Road snapshot begin: expected=%d", event->count);
    }
    /* Road fetch coordinates describe downloaded coverage, never override live GPS. */
    if (event->type == ROUNDNAV_EVENT_ROAD_CENTER && !s_gps_valid) {
        s_center_lat_e6 = event->lat_e6;
        s_center_lon_e6 = event->lon_e6;
        s_center_valid = true;
    }
    const int changed = road_store_apply(s_road_store, event);
    if (changed < 0) ESP_LOGW(TAG, "Incomplete/invalid road batch; keeping previous roads");
    if (changed > 0) {
        if (s_map_view) {
            s_cache_dirty = true;
            wake_display();
        } else {
            render_roads(true);
            render_route();
            raise_map_overlays();
        }
        if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_END) {
            int active_roads = 0;
            int buildings = 0;
            for (int r = 0; r < ROAD_MAX_COUNT; ++r) {
                const road_t *road = &s_road_store->roads[r];
                if (!road->active) continue;
                if (road->road_class == ROAD_CLASS_BUILDING) buildings++;
                else active_roads++;
            }
            ESP_LOGI(TAG, "Road snapshot committed: roads=%d buildings=%d", active_roads, buildings);
        }
    }
    ui_unlock();
}

void nav_ui_apply_gps(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    const uint32_t now = lv_tick_get();
    advance_camera(now); /* Rebase an interrupted animation at its displayed position. */
    const double north = (event->lat_e6 - s_center_lat_e6) * (EARTH_M * 0.017453292519943295 / 1000000.0);
    const double east = (event->lon_e6 - s_center_lon_e6) * (EARTH_M * 0.017453292519943295 / 1000000.0)
        * cos(s_center_lat_e6 * 0.017453292519943295 / 1000000.0);
    const bool tracking = event->has_route_progress && s_route_count >= 2 && s_route_meters[s_route_count - 1] > 0;
    const bool snap = !s_gps_valid || !s_camera_refresh_ready || hypot(north, east) > CAMERA_SNAP_M ||
        (tracking && !s_route_tracking);
    if (!tracking && s_route_tracking) set_navigation_cue('S', -1);
    s_route_tracking = tracking;
    s_camera_follows_route = tracking;
    s_progress_from = s_route_progress;
    s_progress_to = event->route_progress_dm / 10.0;
    uint32_t interval = s_gps_valid ? now - s_last_fix_tick : 1000;
    if (interval < 200) interval = 200;
    if (interval > 1500) interval = 1500;
    if (tracking && interval > 350) interval = 350;
    s_last_fix_tick = now;
    s_camera_from_lat = s_center_lat_e6;
    s_camera_from_lon = s_center_lon_e6;
    s_camera_to_lat = event->lat_e6;
    s_camera_to_lon = event->lon_e6;
    s_heading_from = s_heading_deg;
    /* Turn across 359 -> 0 via the shortest arc, rather than almost a full circle. */
    s_heading_delta = fmodf((float)event->bearing_deg - s_heading_deg + 540.0f, 360.0f) - 180.0f;
    s_camera_start = now;
    s_camera_duration = interval;
    s_center_valid = true;
    s_gps_valid = true;
    s_camera_animating = !snap && (fabs(s_camera_to_lat - s_camera_from_lat) > 0.001
        || fabs(s_camera_to_lon - s_camera_from_lon) > 0.001 || fabsf(s_heading_delta) > 0.01f
        || (tracking && fabs(s_progress_to - s_progress_from) > 0.001));
    if (s_camera_animating) wake_display();
    if (snap) {
        s_center_lat_e6 = event->lat_e6;
        s_center_lon_e6 = event->lon_e6;
        if (tracking) apply_route_progress(s_progress_to);
        set_map_heading((float)event->bearing_deg);
        if (s_map_view) {
            s_cache_dirty = true;
            wake_display();
        } else {
            render_roads(true);
            render_route();
            raise_map_overlays();
        }
    }
    /* No extrapolation: stop at the last received fix if GPS/BLE stalls. */
    ui_unlock();
}
