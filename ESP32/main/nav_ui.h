#pragma once
#include "roundnav_events.h"
void nav_ui_init(void);
void nav_ui_apply_nav(const roundnav_event_t *event);
void nav_ui_apply_map_event(const roundnav_event_t *event);
void nav_ui_apply_road_event(const roundnav_event_t *event);
void nav_ui_apply_gps(const roundnav_event_t *event);
void nav_ui_set_ble_state(bool connected);
