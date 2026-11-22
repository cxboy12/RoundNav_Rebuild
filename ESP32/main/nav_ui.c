#include <stdio.h>
#include <string.h>
#include <math.h>
#include "esp_log.h"
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

static const char *TAG = "RoundNavUI";

static lv_obj_t *s_map_clip;
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
static int32_t s_center_lat_e6;
static int32_t s_center_lon_e6;
static bool s_center_valid;
static bool s_gps_valid;
static float s_heading_deg;
static char s_direction = 'S';
static int32_t s_distance_m = -1;

static bool ui_lock(void) { return bsp_display_lock(1000) == ESP_OK; }
static void ui_unlock(void) { bsp_display_unlock(); }

static void project_point(const geo_point_t *p, lv_point_precise_t *out)
{
    if (!s_center_valid) { out->x = MAP_RADIUS_PX; out->y = MAP_RADIUS_PX; return; }
    const float lat0 = (float)s_center_lat_e6 / 1000000.0f;
    const float d2r = 0.017453292519943295f;
    /* Subtract integer coordinates first: absolute float longitude loses detail. */
    const float east = (float)(p->lon - s_center_lon_e6) / 1000000.0f * d2r * EARTH_M * cosf(lat0 * d2r);
    const float north = (float)(p->lat - s_center_lat_e6) / 1000000.0f * d2r * EARTH_M;
    const float ppm = MAP_RADIUS_PX / MAP_RADIUS_M;
    out->x = (lv_coord_t)(MAP_RADIUS_PX + east * ppm);
    out->y = (lv_coord_t)(MAP_RADIUS_PX - north * ppm);
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

static void render_roads(void)
{
    if (s_map_clip == NULL) return;
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
        }
        lv_obj_remove_flag(s_road_lines[r], LV_OBJ_FLAG_HIDDEN);
        lv_line_set_points(s_road_lines[r], s_road_screen[r], road->count);
        lv_obj_set_style_line_color(s_road_lines[r], road_color(road->road_class), 0);
        lv_obj_set_style_line_opa(s_road_lines[r], LV_OPA_COVER, 0);
        lv_obj_set_style_line_width(s_road_lines[r], road_width(road->road_class), 0);
        lv_obj_set_style_line_rounded(s_road_lines[r], true, 0);
        lv_obj_clear_flag(s_road_lines[r], LV_OBJ_FLAG_CLICKABLE);
    }
}

static void render_route(void)
{
    if (s_route_count < 2 || s_map_clip == NULL) {
        if (s_route_line) lv_obj_add_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
        return;
    }
    for (int i = 0; i < s_route_count; ++i) project_point(&s_route[i], &s_route_screen[i]);
    if (s_route_line == NULL) {
        s_route_line = lv_line_create(s_map_clip);
        lv_obj_set_pos(s_route_line, 0, 0);
        lv_obj_set_size(s_route_line, MAP_DIAMETER_PX, MAP_DIAMETER_PX);
    }
    lv_obj_remove_flag(s_route_line, LV_OBJ_FLAG_HIDDEN);
    lv_line_set_points(s_route_line, s_route_screen, s_route_count);
    lv_obj_set_style_line_color(s_route_line, lv_color_hex(0x48B8FF), 0);
    lv_obj_set_style_line_width(s_route_line, 6, 0);
    lv_obj_set_style_line_rounded(s_route_line, true, 0);
    lv_obj_clear_flag(s_route_line, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_move_foreground(s_route_line);
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
    for (int i = 0; i < 3; ++i) {
        if (s_nav_arrow_lines[i] == NULL) {
            s_nav_arrow_lines[i] = lv_line_create(lv_screen_active());
            lv_obj_set_pos(s_nav_arrow_lines[i], 0, 0);
            lv_obj_set_size(s_nav_arrow_lines[i], 466, 466);
            lv_obj_set_style_line_color(s_nav_arrow_lines[i], lv_color_white(), 0);
            lv_obj_set_style_line_width(s_nav_arrow_lines[i], 6, 0);
            lv_obj_set_style_line_rounded(s_nav_arrow_lines[i], true, 0);
            lv_obj_clear_flag(s_nav_arrow_lines[i], LV_OBJ_FLAG_CLICKABLE);
        }
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
    lv_label_set_text(s_nav_label, word);
    draw_nav_arrow();
    char d[32];
    if (s_distance_m < 0) snprintf(d, sizeof(d), "-- m");
    else if (s_distance_m >= 1000) snprintf(d, sizeof(d), "%.1f km", (double)s_distance_m / 1000.0);
    else snprintf(d, sizeof(d), "%ld m", (long)s_distance_m);
    lv_label_set_text(s_distance_label, d);
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

    s_nav_label = lv_label_create(screen);
    lv_label_set_text(s_nav_label, "STRAIGHT");
    lv_obj_set_style_text_color(s_nav_label, lv_color_white(), 0);
    lv_obj_set_style_text_font(s_nav_label, LV_FONT_DEFAULT, 0);
    lv_obj_align(s_nav_label, LV_ALIGN_TOP_MID, 0, 52);

    s_distance_label = lv_label_create(screen);
    lv_label_set_text(s_distance_label, "-- m");
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
    ui_unlock();
}

void nav_ui_set_ble_state(bool connected)
{
    if (!ui_lock()) return;
    if (!connected) road_store_abort(&s_road_store);
    if (s_ble_label) {
        lv_label_set_text(s_ble_label, connected ? "BLE CONNECTED" : "BLE WAIT");
        lv_obj_set_style_text_color(s_ble_label, connected ? lv_color_hex(0x4DD69A) : lv_color_hex(0x788797), 0);
    }
    ui_unlock();
}

void nav_ui_apply_nav(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    s_direction = event->direction;
    s_distance_m = event->distance_m;
    update_nav_label();
    ui_unlock();
}

void nav_ui_apply_map_event(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    if (event->type == ROUNDNAV_EVENT_MAP_BEGIN) {
        s_route_count = 0;
    } else if (event->type == ROUNDNAV_EVENT_MAP_POINT) {
        if (event->index >= 0 && event->index < ROUTE_MAX_POINTS) {
            s_route[event->index] = (geo_point_t){ .lat = event->lat_e6, .lon = event->lon_e6 };
            if (s_route_count <= event->index) s_route_count = event->index + 1;
        }
    } else if (event->type == ROUNDNAV_EVENT_MAP_END) {
        render_route();
        draw_position_marker();
    }
    ui_unlock();
}

void nav_ui_apply_road_event(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    /* Road fetch coordinates describe downloaded coverage, never override live GPS. */
    if (event->type == ROUNDNAV_EVENT_ROAD_CENTER && !s_gps_valid) {
        s_center_lat_e6 = event->lat_e6;
        s_center_lon_e6 = event->lon_e6;
        s_center_valid = true;
    }
    const int changed = road_store_apply(&s_road_store, event);
    if (changed < 0) ESP_LOGW(TAG, "Incomplete/invalid road batch; keeping previous roads");
    if (changed > 0) {
        render_roads();
        render_route();
        draw_position_marker();
        if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_END) ESP_LOGI(TAG, "Road snapshot committed");
    }
    ui_unlock();
}

void nav_ui_apply_gps(const roundnav_event_t *event)
{
    if (event == NULL || !ui_lock()) return;
    s_center_lat_e6 = event->lat_e6;
    s_center_lon_e6 = event->lon_e6;
    s_center_valid = true;
    s_gps_valid = true;
    s_heading_deg = (float)event->bearing_deg;
    /* Each accepted fix reprojects cached roads; no network request is needed to pan. */
    render_roads();
    render_route();
    draw_position_marker();
    ui_unlock();
}
