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
#define NORTH_PIXELS_PER_E6 (0.017453292519943295 * EARTH_M / 1000000.0 * MAP_RADIUS_PX / MAP_RADIUS_M)

static const char *TAG = "RoundNavUI";

static lv_obj_t *s_map_clip;
static lv_obj_t *s_road_bitmap;
static double s_bitmap_lat, s_bitmap_lon;
static float s_bitmap_east_scale;
static int s_bitmap_dx, s_bitmap_dy;
static bool s_bitmap_valid;
static lv_obj_t *s_ble_label;
static lv_obj_t *s_nav_label;
static lv_obj_t *s_distance_label;
static lv_obj_t *s_nav_arrow_lines[3];
static lv_point_precise_t s_nav_arrow_points[3][2];
static lv_obj_t *s_route_line;
static lv_obj_t *s_road_lines[ROAD_MAX_COUNT];
static lv_obj_t *s_marker_lines[MARKER_ROWS];
static lv_point_precise_t s_road_screen[ROAD_MAX_COUNT][ROAD_MAX_POINTS];
static lv_point_precise_t s_route_screen[ROUTE_MAX_POINTS];
static lv_point_precise_t s_marker_points[MARKER_ROWS][2];
static road_store_t s_road_store;
static geo_point_t s_route[ROUTE_MAX_POINTS];
static int s_route_count;
static geo_point_t s_pending_route[ROUTE_MAX_POINTS];
static int s_pending_route_expected;
static int s_pending_route_count;
/* Double preserves sub-metre movement at absolute longitude ~121 degrees. */
static double s_center_lat_e6;
static double s_center_lon_e6;
static bool s_center_valid;
static bool s_gps_valid;
static float s_heading_deg;
static double s_camera_from_lat, s_camera_from_lon;
static double s_camera_to_lat, s_camera_to_lon;
static float s_heading_from, s_heading_delta;
static uint32_t s_camera_start, s_camera_duration, s_last_fix_tick;
static bool s_camera_animating;
static bool s_camera_refresh_ready;
static float s_east_pixels_per_e6;
static char s_direction = 'S';
static int32_t s_distance_m = -1;

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

static void project_point(const geo_point_t *p, lv_point_precise_t *out)
{
    if (!s_center_valid) { out->x = MAP_RADIUS_PX; out->y = MAP_RADIUS_PX; return; }
    /* Subtract in double before converting to float; preserve local detail. */
    out->x = (lv_coord_t)(MAP_RADIUS_PX + (float)(p->lon - s_center_lon_e6) * s_east_pixels_per_e6);
    out->y = (lv_coord_t)(MAP_RADIUS_PX - (float)(p->lat - s_center_lat_e6) * NORTH_PIXELS_PER_E6);
}

static void update_projection_scale(void)
{
    /* One cosine per redraw, rather than one per road point on every frame. */
    s_east_pixels_per_e6 = 0.017453292519943295 * EARTH_M / 1000000.0 * MAP_RADIUS_PX / MAP_RADIUS_M
        * cos(s_center_lat_e6 * 0.017453292519943295 / 1000000.0);
}

static lv_color_t road_color(int road_class)
{
    switch (road_class) {
    case 0: return lv_color_hex(0xA7B5C5); /* motorway */
    case 1: return lv_color_hex(0x99A8BB); /* trunk */
    case 2: return lv_color_hex(0x8A9BAE); /* primary */
    case 3: return lv_color_hex(0x7B8DA0); /* secondary */
    default: return lv_color_hex(0x687D92); /* local roads */
    }
}

static int road_width(int road_class)
{
    if (road_class <= 0) return 6;
    if (road_class == 1) return 5;
    if (road_class == 2) return 4;
    if (road_class == 3) return 3;
    return 2;
}

static void rebuild_road_bitmap(void)
{
    const int64_t started_us = esp_timer_get_time();
    update_projection_scale();
    s_bitmap_lat = s_center_lat_e6;
    s_bitmap_lon = s_center_lon_e6;
    s_bitmap_east_scale = s_east_pixels_per_e6;
    lv_canvas_fill_bg(s_road_bitmap, lv_color_hex(0x0A1018), LV_OPA_COVER);
    for (int r = 0; r < ROAD_MAX_COUNT; ++r) {
        const road_t *road = &s_road_store.roads[r];
        if (!road->active || road->count < 2) continue;
        lv_layer_t layer;
        lv_canvas_init_layer(s_road_bitmap, &layer);
        lv_draw_line_dsc_t line;
        lv_draw_line_dsc_init(&line);
        line.color = road_color(road->road_class);
        line.width = road_width(road->road_class);
        line.round_start = line.round_end = true;
        for (int i = 0; i < road->count; ++i) {
            project_point(&road->points[i], &line.p2);
            line.p2.x += ROAD_MARGIN_PX;
            line.p2.y += ROAD_MARGIN_PX;
            if (i > 0) lv_draw_line(&layer, &line);
            line.p1 = line.p2;
        }
        lv_canvas_finish_layer(s_road_bitmap, &layer);
    }
    s_bitmap_valid = true;
    const int64_t duration_us = esp_timer_get_time() - started_us;
    if (duration_us > s_display_perf.rebuild_max_us) s_display_perf.rebuild_max_us = duration_us;
}

static void render_roads(bool refresh_style)
{
    if (s_map_clip == NULL) return;
    if (s_road_bitmap) {
        int dx = (int)lround((s_bitmap_lon - s_center_lon_e6) * s_bitmap_east_scale);
        int dy = (int)lround((s_center_lat_e6 - s_bitmap_lat) * NORTH_PIXELS_PER_E6);
        if (refresh_style || !s_bitmap_valid || abs(dx) > ROAD_MARGIN_PX || abs(dy) > ROAD_MARGIN_PX) {
            rebuild_road_bitmap();
            dx = dy = 0;
        }
        if (dx != s_bitmap_dx || dy != s_bitmap_dy) {
            lv_obj_set_pos(s_road_bitmap, dx - ROAD_MARGIN_PX, dy - ROAD_MARGIN_PX);
            s_bitmap_dx = dx;
            s_bitmap_dy = dy;
        }
        return;
    }
    update_projection_scale();
    for (int r = 0; r < ROAD_MAX_COUNT; ++r) {
        road_t *road = &s_road_store.roads[r];
        if (!road->active || road->count < 2) {
            if (s_road_lines[r]) lv_obj_add_flag(s_road_lines[r], LV_OBJ_FLAG_HIDDEN);
            continue;
        }
        for (int i = 0; i < road->count; ++i) project_point(&road->points[i], &s_road_screen[r][i]);

        if (s_road_lines[r] == NULL) {
            s_road_lines[r] = lv_line_create(s_map_clip);
            lv_obj_set_pos(s_road_lines[r], 0, 0);
            lv_obj_set_size(s_road_lines[r], MAP_DIAMETER_PX, MAP_DIAMETER_PX);
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
    if (s_route_count < 2 || s_map_clip == NULL) {
        if (s_route_line) lv_obj_add_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
        return;
    }
    update_projection_scale();
    for (int i = 0; i < s_route_count; ++i) project_point(&s_route[i], &s_route_screen[i]);
    if (s_route_line == NULL) {
        s_route_line = lv_line_create(s_map_clip);
        lv_obj_set_pos(s_route_line, 0, 0);
        lv_obj_set_size(s_route_line, MAP_DIAMETER_PX, MAP_DIAMETER_PX);
        lv_obj_set_style_line_color(s_route_line, lv_color_hex(0x48B8FF), 0);
        lv_obj_set_style_line_width(s_route_line, 6, 0);
        lv_obj_set_style_line_rounded(s_route_line, true, 0);
        lv_obj_clear_flag(s_route_line, LV_OBJ_FLAG_CLICKABLE);
    }
    lv_obj_remove_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
    lv_line_set_points(s_route_line, s_route_screen, s_route_count);
}

static void draw_position_marker(void)
{
    if (s_map_clip == NULL) return;
    const float angle = s_heading_deg * 0.017453292519943295f;
    const float cs = cosf(angle), sn = sinf(angle);
    for (int i = 0; i < MARKER_ROWS; ++i) {
        if (s_marker_lines[i] == NULL) {
            s_marker_lines[i] = lv_line_create(s_map_clip);
            lv_obj_set_pos(s_marker_lines[i], 0, 0);
            lv_obj_set_size(s_marker_lines[i], MAP_DIAMETER_PX, MAP_DIAMETER_PX);
            lv_obj_set_style_line_color(s_marker_lines[i], lv_color_white(), 0);
            lv_obj_set_style_line_width(s_marker_lines[i], 2, 0);
            lv_obj_set_style_line_rounded(s_marker_lines[i], true, 0);
            lv_obj_clear_flag(s_marker_lines[i], LV_OBJ_FLAG_CLICKABLE);
        }
        const float local_y = -10.0f + 2.0f * i;
        const float half = (float)i;
        const float lx[2] = {-half, half};
        for (int k = 0; k < 2; ++k) {
            const float ly = local_y;
            s_marker_points[i][k].x = (lv_coord_t)(MAP_RADIUS_PX + lx[k] * cs - ly * sn);
            s_marker_points[i][k].y = (lv_coord_t)(MAP_RADIUS_PX + lx[k] * sn + ly * cs);
        }
        lv_line_set_points(s_marker_lines[i], s_marker_points[i], 2);
        lv_obj_move_foreground(s_marker_lines[i]);
    }
}

/* Called with the LVGL lock held (timer callbacks already hold that lock). */
static void advance_camera(uint32_t now)
{
    if (!s_camera_animating) return;
    const uint32_t elapsed = now - s_camera_start;
    const double t = elapsed >= s_camera_duration ? 1.0 : (double)elapsed / s_camera_duration;
    s_center_lat_e6 = s_camera_from_lat + (s_camera_to_lat - s_camera_from_lat) * t;
    s_center_lon_e6 = s_camera_from_lon + (s_camera_to_lon - s_camera_from_lon) * t;
    const float heading = fmodf(s_heading_from + s_heading_delta * (float)t + 360.0f, 360.0f);
    render_roads(false);
    render_route();
    if (fabsf(heading - s_heading_deg) > 0.01f) {
        s_heading_deg = heading;
        draw_position_marker();
    }
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
        if (s_camera_animating) {
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
    const lv_point_precise_t straight[3][2] = {
        {{233, 39}, {233, 10}}, {{233, 10}, {222, 22}}, {{233, 10}, {244, 22}}
    };
    const lv_point_precise_t left[3][2] = {
        {{245, 25}, {217, 25}}, {{217, 25}, {228, 14}}, {{217, 25}, {228, 36}}
    };
    const lv_point_precise_t right[3][2] = {
        {{221, 25}, {249, 25}}, {{249, 25}, {238, 14}}, {{249, 25}, {238, 36}}
    };
    const lv_point_precise_t uturn[3][2] = {
        {{233, 10}, {233, 39}}, {{233, 39}, {222, 28}}, {{233, 39}, {244, 28}}
    };
    const lv_point_precise_t (*src)[2] = straight;
    if (s_direction == 'L') src = left;
    else if (s_direction == 'R') src = right;
    else if (s_direction == 'U') src = uturn;
    const bool hide_arrow = s_direction == 'S' && s_distance_m <= 0;
    for (int i = 0; i < 3; ++i) {
        if (s_nav_arrow_lines[i] == NULL) {
            s_nav_arrow_lines[i] = lv_line_create(lv_screen_active());
            lv_obj_set_pos(s_nav_arrow_lines[i], 0, 0);
            lv_obj_set_size(s_nav_arrow_lines[i], 466, 466);
            lv_obj_set_style_line_color(s_nav_arrow_lines[i], lv_color_white(), 0);
            lv_obj_set_style_line_width(s_nav_arrow_lines[i], 6, 0);
            lv_obj_set_style_line_rounded(s_nav_arrow_lines[i], true, 0);
            lv_obj_set_clickable(s_nav_arrow_lines[i], false);
        }
        lv_obj_set_hidden(s_nav_arrow_lines[i], hide_arrow);
        if (hide_arrow) continue;
        s_nav_arrow_points[i][0] = src[i][0];
        s_nav_arrow_points[i][1] = src[i][1];
        lv_line_set_points(s_nav_arrow_lines[i], s_nav_arrow_points[i], 2);
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
    lv_label_set_text(s_nav_label, word);
    draw_nav_arrow();
    char d[32];
    if (s_direction == 'S' && s_distance_m <= 0) d[0] = '\0';
    else if (s_distance_m < 0) snprintf(d, sizeof(d), "-- m");
    else if (s_distance_m >= 1000) snprintf(d, sizeof(d), "%.1f km", (double)s_distance_m / 1000.0);
    else snprintf(d, sizeof(d), "%ld m", (long)s_distance_m);
    lv_label_set_text(s_distance_label, d);
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
            if (distance >= MAP_RADIUS_PX) row[x] = 0xFF05080D;
            else if (distance >= MAP_RADIUS_PX - 2.0f) row[x] = 0xFF344252;
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

void nav_ui_init(void)
{
    if (!ui_lock()) { ESP_LOGE(TAG, "Could not lock LVGL during UI init"); return; }
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x05080D), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);

    s_map_clip = lv_obj_create(screen);
    lv_obj_set_size(s_map_clip, MAP_DIAMETER_PX, MAP_DIAMETER_PX);
    lv_obj_align(s_map_clip, LV_ALIGN_CENTER, 0, 0);
    lv_obj_set_style_radius(s_map_clip, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_clip_corner(s_map_clip, true, 0);
    lv_obj_set_style_bg_color(s_map_clip, lv_color_hex(0x0A1018), 0);
    lv_obj_set_style_bg_opa(s_map_clip, LV_OPA_COVER, 0);
    lv_obj_set_style_border_color(s_map_clip, lv_color_hex(0x344252), 0);
    lv_obj_set_style_border_width(s_map_clip, 2, 0);
    lv_obj_set_style_pad_all(s_map_clip, 0, 0);
    lv_obj_clear_flag(s_map_clip, LV_OBJ_FLAG_SCROLLABLE);

    const size_t stride = lv_draw_buf_width_to_stride(ROAD_BITMAP_PX, LV_COLOR_FORMAT_RGB565);
    void *pixels = heap_caps_aligned_alloc(LV_DRAW_BUF_ALIGN, stride * ROAD_BITMAP_PX,
                                          MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (pixels) {
        s_road_bitmap = lv_canvas_create(s_map_clip);
        if (s_road_bitmap) {
            lv_canvas_set_buffer(s_road_bitmap, pixels, ROAD_BITMAP_PX, ROAD_BITMAP_PX, LV_COLOR_FORMAT_RGB565);
            lv_canvas_fill_bg(s_road_bitmap, lv_color_hex(0x0A1018), LV_OPA_COVER);
            lv_obj_set_pos(s_road_bitmap, -ROAD_MARGIN_PX, -ROAD_MARGIN_PX);
        } else {
            heap_caps_free(pixels);
        }
    }
    ESP_LOGI(TAG, "Road renderer: %s", s_road_bitmap ? "cached bitmap" : "vector fallback");
    create_map_surround(screen);

    s_nav_label = lv_label_create(screen);
    lv_label_set_text(s_nav_label, "");
    lv_obj_set_style_text_color(s_nav_label, lv_color_white(), 0);
    lv_obj_set_style_text_font(s_nav_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_nav_label, LV_ALIGN_TOP_MID, 0, 52);

    s_distance_label = lv_label_create(screen);
    lv_label_set_text(s_distance_label, "");
    lv_obj_set_style_text_color(s_distance_label, lv_color_hex(0xD4DCE5), 0);
    lv_obj_set_style_text_font(s_distance_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_distance_label, LV_ALIGN_TOP_MID, 0, 82);

    s_ble_label = lv_label_create(screen);
    lv_label_set_text(s_ble_label, "BLE WAIT");
    lv_obj_set_style_text_color(s_ble_label, lv_color_hex(0x788797), 0);
    lv_obj_set_style_text_font(s_ble_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_ble_label, LV_ALIGN_BOTTOM_MID, 0, -12);

    memset(&s_road_store, 0, sizeof(s_road_store));
    s_route_count = 0;
    draw_nav_arrow();
    draw_position_marker();
    lv_display_t *display = lv_display_get_default();
    lv_timer_t *refresh = lv_display_get_refr_timer(display);
    if (refresh) lv_timer_set_period(refresh, CAMERA_FRAME_MS);
    s_camera_refresh_ready = lv_display_add_event_cb(display, display_event, LV_EVENT_ALL, NULL) != NULL && refresh != NULL;
    s_display_perf.window_start_us = esp_timer_get_time();
    ESP_LOGI(TAG, "Display motion on LVGL refresh, period=%d ms, metrics=%s",
             CAMERA_FRAME_MS, s_camera_refresh_ready ? "enabled" : "unavailable");
    ui_unlock();
}

void nav_ui_set_ble_state(bool connected)
{
    if (!ui_lock()) return;
    if (!connected) {
        road_store_abort(&s_road_store);
        s_pending_route_expected = 0;
        s_camera_animating = false;
        s_gps_valid = false; /* First fix after reconnect snaps to its actual location. */
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

void nav_ui_apply_nav(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    const bool new_direction = s_direction != event->direction;
    s_direction = event->direction;
    s_distance_m = event->distance_m;
    update_nav_label();
    if (s_direction == 'S' && s_distance_m == 0) {
        ESP_LOGI(TAG, "Navigation cue applied: arrival");
    } else if (new_direction) {
        ESP_LOGI(TAG, "Navigation cue applied: %c", s_direction);
    }
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
        if (index >= 0 && index < s_pending_route_expected && index == s_pending_route_count) {
            s_pending_route[index] = (geo_point_t){ .lat = event->lat_e6, .lon = event->lon_e6 };
            ++s_pending_route_count;
        } else if (!(index >= 0 && index < s_pending_route_expected && index < s_pending_route_count &&
                     s_pending_route[index].lat == event->lat_e6 && s_pending_route[index].lon == event->lon_e6)) {
            /* A retried ATT packet can repeat points already queued by its first write. */
            s_pending_route_expected = 0;
        }
    } else if (event->type == ROUNDNAV_EVENT_MAP_END) {
        if (s_pending_route_expected > 0 && s_pending_route_count == s_pending_route_expected) {
            s_route_count = s_pending_route_count;
            memcpy(s_route, s_pending_route, s_route_count * sizeof(*s_route));
            render_route();
            if (s_route_line) lv_obj_move_foreground(s_route_line);
            draw_position_marker();
            ESP_LOGI(TAG, "Route committed: points=%d", s_route_count);
        } else {
            ESP_LOGW(TAG, "Incomplete/invalid route; keeping previous route");
        }
        s_pending_route_expected = 0;
    }
    ui_unlock();
}

void nav_ui_apply_road_event(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN) {
        ESP_LOGI(TAG, "Road snapshot begin: expected=%d", event->count);
    }
    /* Road fetch coordinates describe downloaded coverage, never override live GPS. */
    if (event->type == ROUNDNAV_EVENT_ROAD_CENTER && !s_gps_valid) {
        s_center_lat_e6 = event->lat_e6;
        s_center_lon_e6 = event->lon_e6;
        s_center_valid = true;
    }
    const int changed = road_store_apply(&s_road_store, event);
    if (changed < 0) ESP_LOGW(TAG, "Incomplete/invalid road batch; keeping previous roads");
    if (changed > 0) {
        render_roads(true);
        render_route();
        if (s_route_line) lv_obj_move_foreground(s_route_line);
        draw_position_marker();
        if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_END) {
            int active_roads = 0;
            for (int r = 0; r < ROAD_MAX_COUNT; ++r) {
                if (s_road_store.roads[r].active) active_roads++;
            }
            ESP_LOGI(TAG, "Road snapshot committed: roads=%d", active_roads);
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
    const bool snap = !s_gps_valid || !s_camera_refresh_ready || hypot(north, east) > CAMERA_SNAP_M;
    uint32_t interval = s_gps_valid ? now - s_last_fix_tick : 1000;
    if (interval < 200) interval = 200;
    if (interval > 1500) interval = 1500;
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
        || fabs(s_camera_to_lon - s_camera_from_lon) > 0.001 || fabsf(s_heading_delta) > 0.01f);
    if (s_camera_animating) {
        lv_timer_t *refresh = lv_display_get_refr_timer(lv_display_get_default());
        if (refresh) {
            lv_timer_resume(refresh);
            lv_timer_ready(refresh);
        }
    }
    if (snap) {
        s_center_lat_e6 = event->lat_e6;
        s_center_lon_e6 = event->lon_e6;
        s_heading_deg = (float)event->bearing_deg;
        render_roads(false);
        render_route();
        draw_position_marker();
    }
    /* No extrapolation: stop at the last received fix if GPS/BLE stalls. */
    ui_unlock();
}
