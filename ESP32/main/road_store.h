#pragma once
#include "roundnav_events.h"

#define ROAD_MAX_COUNT 40
#define ROAD_MAX_POINTS 80
#define ROAD_CLASS_BUILDING 5
#define BUILDING_MAX_POINTS 12

typedef struct { int32_t lat; int32_t lon; } geo_point_t;
typedef struct {
    geo_point_t points[ROAD_MAX_POINTS];
    uint8_t received[ROAD_MAX_POINTS];
    int count;
    int road_class;
    bool active;
} road_t;

typedef struct {
    road_t roads[ROAD_MAX_COUNT];
    road_t pending[ROAD_MAX_COUNT];
    bool receiving;
    int expected_roads;
} road_store_t;

/* 1: visible roads changed; 0: still receiving; -1: invalid/incomplete batch. */
int road_store_apply(road_store_t *store, const roundnav_event_t *event);
void road_store_abort(road_store_t *store);
