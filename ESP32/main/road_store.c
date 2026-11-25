#include "road_store.h"
#include <string.h>

void road_store_abort(road_store_t *store)
{
    store->receiving = false;
    store->expected_roads = 0;
}

int road_store_apply(road_store_t *store, const roundnav_event_t *event)
{
    if (!store || !event) return -1;
    if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN) {
        if (event->count < 0 || event->count > ROAD_MAX_COUNT) return -1;
        memset(store->pending, 0, sizeof(store->pending));
        store->expected_roads = event->count;
        store->receiving = true;
        return 0;
    }
    if (event->type == ROUNDNAV_EVENT_ROAD_SNAPSHOT_END) {
        if (!store->receiving) return -1;
        bool complete = true;
        for (int i = 0; i < store->expected_roads; ++i) {
            if (!store->pending[i].active) complete = false;
        }
        road_store_abort(store);
        if (!complete) return -1;
        memcpy(store->roads, store->pending, sizeof(store->roads));
        return 1;
    }
    if (event->type == ROUNDNAV_EVENT_ROAD_CENTER) {
        /* A snapshot keeps the old map visible until RF commits all new roads. */
        if (store->receiving) return 0;
        memset(store->roads, 0, sizeof(store->roads));
        return 1;
    }
    const int id = event->road_id;
    if (id < 0 || id >= ROAD_MAX_COUNT || (store->receiving && id >= store->expected_roads)) return -1;
    road_t *road = store->receiving ? &store->pending[id] : &store->roads[id];
    switch (event->type) {
    case ROUNDNAV_EVENT_ROAD_BEGIN:
        if (event->count < 2 || event->count > ROAD_MAX_POINTS || event->road_class < 0 ||
            event->road_class > ROAD_CLASS_BUILDING) return -1;
        if (event->road_class == ROAD_CLASS_BUILDING &&
            (event->count < 4 || event->count > BUILDING_MAX_POINTS)) return -1;
        memset(road, 0, sizeof(*road));
        road->count = event->count;
        road->road_class = event->road_class;
        return 0;
    case ROUNDNAV_EVENT_ROAD_POINT:
        if (event->index < 0 || event->index >= road->count) return -1;
        road->points[event->index] = (geo_point_t){event->lat_e6, event->lon_e6};
        road->received[event->index] = 1;
        return 0;
    case ROUNDNAV_EVENT_ROAD_END:
        if (road->count < 2) return -1;
        for (int i = 0; i < road->count; ++i) if (!road->received[i]) return -1;
        road->active = true;
        return store->receiving ? 0 : 1;
    default:
        return -1;
    }
}
