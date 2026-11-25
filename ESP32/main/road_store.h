#pragma once
#include "roundnav_events.h"

/* One snapshot holds up to 64 street pieces and 96 building footprints.
 * Must match RoadMap.MAX_ENTRIES on Android. The store lives in PSRAM. */
#define ROAD_MAX_COUNT 160
/* Android clips streets to the visible area and sends at most 24 points. */
#define ROAD_MAX_POINTS 32
#define ROAD_CLASS_BUILDING 5
/* Service roads and alleys (hem): drawn thinner than ordinary streets. */
#define ROAD_CLASS_SERVICE 6
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
