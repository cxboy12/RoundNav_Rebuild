#pragma once
#include <stdbool.h>
#include <stdint.h>

typedef enum {
    ROUNDNAV_EVENT_NAV = 1,
    ROUNDNAV_EVENT_LINK = 2,
    ROUNDNAV_EVENT_MAP_BEGIN = 3,
    ROUNDNAV_EVENT_MAP_POINT = 4,
    ROUNDNAV_EVENT_MAP_END = 5,
    ROUNDNAV_EVENT_ROAD_BEGIN = 6,
    ROUNDNAV_EVENT_ROAD_POINT = 7,
    ROUNDNAV_EVENT_ROAD_END = 8,
    ROUNDNAV_EVENT_ROAD_CENTER = 9,
    ROUNDNAV_EVENT_GPS = 10,
    ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN = 11,
    ROUNDNAV_EVENT_ROAD_SNAPSHOT_END = 12
} roundnav_event_type_t;

typedef struct {
    roundnav_event_type_t type;
    char direction;
    int32_t distance_m;
    int32_t speed_limit;
    bool connected;
    int32_t index;
    int32_t count;
    int32_t lat_e6;
    int32_t lon_e6;
    int32_t road_id;
    int32_t road_class;
    int32_t bearing_deg;
} roundnav_event_t;
