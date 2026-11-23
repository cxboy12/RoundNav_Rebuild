#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include "esp_log.h"
#include "esp_err.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/ble_gatt.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "roundnav_ble.h"
#include "roundnav_events.h"

static const char *TAG = "RoundNavBLE";
static QueueHandle_t s_event_queue;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static void roundnav_ble_advertise(void);

/* Same custom 128-bit UUIDs used by the RoundNav Android bridge. */
static const ble_uuid128_t s_service_uuid = BLE_UUID128_INIT(
    0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x01,0x00,0x40,0x6e);
static const ble_uuid128_t s_rx_uuid = BLE_UUID128_INIT(
    0x9e,0xca,0xdc,0x24,0x0e,0xe5,0xa9,0xe0,0x93,0xf3,0xa3,0xb5,0x02,0x00,0x40,0x6e);

static bool post_event(const roundnav_event_t *event)
{
    if (s_event_queue == NULL || event == NULL) return false;
    if (xQueueSend(s_event_queue, event, 0) != pdTRUE) {
        ESP_LOGW(TAG, "UI event queue full; dropped type=%d", (int)event->type);
        return false;
    }
    return true;
}

static int parse_line(char *line)
{
    roundnav_event_t ev = {0};
    char kind = 0;
    int a = 0, b = 0, c = 0, d = 0;
    while (*line == ' ' || *line == '\t') line++;
    size_t n = strlen(line);
    while (n && (line[n - 1] == '\r' || line[n - 1] == '\n' || line[n - 1] == ' ')) line[--n] = '\0';
    if (!n) return 0;

    /* A complete road snapshot is staged between RS,count and RF. */
    if (sscanf(line, "RS,%d", &a) == 1) {
        if (a < 0 || a > 40) return -1;
        ev.type = ROUNDNAV_EVENT_ROAD_SNAPSHOT_BEGIN; ev.count = a;
        return post_event(&ev) ? 0 : -1;
    }
    if (strcmp(line, "RF") == 0) {
        ev.type = ROUNDNAV_EVENT_ROAD_SNAPSHOT_END;
        return post_event(&ev) ? 0 : -1;
    }

    /* GPS: G,latitude_e6,longitude_e6,bearing_degrees */
    if (sscanf(line, " %c , %d , %d , %d", &kind, &a, &b, &c) == 4 && (kind == 'G' || kind == 'g')) {
        if (a < -90000000 || a > 90000000 || b < -180000000 || b > 180000000 || c < 0 || c > 359) return -1;
        ev.type = ROUNDNAV_EVENT_GPS; ev.lat_e6 = a; ev.lon_e6 = b; ev.bearing_deg = c;
        return post_event(&ev) ? 0 : -1;
    }
    /* Nearby-map center: RC,lat_e6,lon_e6 */
    if (sscanf(line, " RC , %d , %d", &a, &b) == 2 && toupper((unsigned char)line[0]) == 'R' && toupper((unsigned char)line[1]) == 'C') {
        if (a < -90000000 || a > 90000000 || b < -180000000 || b > 180000000) return -1;
        ev.type = ROUNDNAV_EVENT_ROAD_CENTER; ev.lat_e6 = a; ev.lon_e6 = b;
        return post_event(&ev) ? 0 : -1;
    }
    /* Road begin: RB,id,count,class */
    if (sscanf(line, " RB , %d , %d , %d", &a, &b, &c) == 3 && toupper((unsigned char)line[0]) == 'R' && toupper((unsigned char)line[1]) == 'B') {
        if (a < 0 || a >= 40 || b < 2 || b > 80 || c < 0 || c > 4) return -1;
        ev.type = ROUNDNAV_EVENT_ROAD_BEGIN; ev.road_id = a; ev.count = b; ev.road_class = c;
        return post_event(&ev) ? 0 : -1;
    }
    /* Road point: RP,id,index,lat_e6,lon_e6 */
    if (sscanf(line, " RP , %d , %d , %d , %d", &a, &b, &c, &d) == 4 && toupper((unsigned char)line[0]) == 'R' && toupper((unsigned char)line[1]) == 'P') {
        if (a < 0 || a >= 40 || b < 0 || b >= 80 || c < -90000000 || c > 90000000 || d < -180000000 || d > 180000000) return -1;
        ev.type = ROUNDNAV_EVENT_ROAD_POINT; ev.road_id = a; ev.index = b; ev.lat_e6 = c; ev.lon_e6 = d;
        return post_event(&ev) ? 0 : -1;
    }
    /* Road end: RE,id */
    if (sscanf(line, " RE , %d", &a) == 1 && toupper((unsigned char)line[0]) == 'R' && toupper((unsigned char)line[1]) == 'E') {
        if (a < 0 || a >= 40) return -1;
        ev.type = ROUNDNAV_EVENT_ROAD_END; ev.road_id = a;
        return post_event(&ev) ? 0 : -1;
    }
    /* Route begin: B,count */
    if (sscanf(line, " %c , %d", &kind, &a) == 2 && (kind == 'B' || kind == 'b')) {
        if (a < 2 || a > 500) return -1;
        ev.type = ROUNDNAV_EVENT_MAP_BEGIN; ev.count = a;
        return post_event(&ev) ? 0 : -1;
    }
    /* Route point: P,index,lat_e6,lon_e6 */
    if (sscanf(line, " %c , %d , %d , %d", &kind, &a, &b, &c) == 4 && (kind == 'P' || kind == 'p')) {
        if (a < 0 || a >= 500 || b < -90000000 || b > 90000000 || c < -180000000 || c > 180000000) return -1;
        ev.type = ROUNDNAV_EVENT_MAP_POINT; ev.index = a; ev.lat_e6 = b; ev.lon_e6 = c;
        return post_event(&ev) ? 0 : -1;
    }
    if (strcmp(line, "E") == 0 || strcmp(line, "e") == 0) {
        ev.type = ROUNDNAV_EVENT_MAP_END;
        return post_event(&ev) ? 0 : -1;
    }
    /* Turn instruction: L,meters,speed; R/S/U also accepted. */
    if (sscanf(line, " %c , %d , %d", &kind, &a, &b) == 3) {
        kind = (char)toupper((unsigned char)kind);
        if (kind != 'L' && kind != 'R' && kind != 'S' && kind != 'U') return -1;
        if (a < -1 || a > 1000000 || b < 0 || b > 350) return -1;
        ev.type = ROUNDNAV_EVENT_NAV; ev.direction = kind; ev.distance_m = a; ev.speed_limit = b;
        return post_event(&ev) ? 0 : -1;
    }
    ESP_LOGW(TAG, "Ignored invalid command: %s", line);
    return -1;
}

static int rx_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle; (void)attr_handle; (void)arg;
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_UNLIKELY;
    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0 || len > 240) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    char data[241];
    if (os_mbuf_copydata(ctxt->om, 0, len, data) != 0) return BLE_ATT_ERR_UNLIKELY;
    data[len] = '\0';
    char *save = NULL;
    char *line = strtok_r(data, "\n", &save);
    while (line != NULL) {
        ESP_LOGD(TAG, "BLE RX: %s", line);
        if (parse_line(line) != 0) {
            ESP_LOGW(TAG, "Command rejected: %s", line);
            return BLE_ATT_ERR_UNLIKELY;
        }
        line = strtok_r(NULL, "\n", &save);
    }
    return 0;
}

static const struct ble_gatt_chr_def s_characteristics[] = {
    { .uuid = &s_rx_uuid.u, .access_cb = rx_access_cb,
      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP },
    { 0 }
};
static const struct ble_gatt_svc_def s_services[] = {
    { .type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &s_service_uuid.u,
      .characteristics = s_characteristics },
    { 0 }
};

static int gap_event_cb(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            roundnav_event_t ev = { .type = ROUNDNAV_EVENT_LINK, .connected = true };
            post_event(&ev);
            ESP_LOGI(TAG, "BLE connected, handle=%d", s_conn_handle);
        } else {
            s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
            roundnav_ble_advertise();
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT: {
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        roundnav_event_t ev = { .type = ROUNDNAV_EVENT_LINK, .connected = false };
        post_event(&ev);
        ESP_LOGI(TAG, "BLE disconnected; restart advertising");
        roundnav_ble_advertise();
        return 0;
    }
    case BLE_GAP_EVENT_ADV_COMPLETE:
        return 0;
    default:
        return 0;
    }
}

static void roundnav_ble_advertise(void)
{
    uint8_t own_addr_type;
    int rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) { ESP_LOGE(TAG, "infer address failed: %d", rc); return; }
    struct ble_hs_adv_fields fields = {0};
    const char *name = "RoundNav";
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;
    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) { ESP_LOGE(TAG, "set adv fields failed: %d", rc); return; }
    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event_cb, NULL);
    if (rc != 0) ESP_LOGE(TAG, "advertising start failed: %d", rc);
    else ESP_LOGI(TAG, "Advertising as %s", name);
}

static void on_sync(void)
{
    roundnav_ble_advertise();
}
static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

esp_err_t roundnav_ble_init(QueueHandle_t event_queue)
{
    if (event_queue == NULL) return ESP_ERR_INVALID_ARG;
    s_event_queue = event_queue;
    esp_err_t init_err = nimble_port_init();
    if (init_err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init failed: %s", esp_err_to_name(init_err));
        return init_err;
    }
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("RoundNav");
    int rc = ble_gatts_count_cfg(s_services);
    if (rc != 0) return ESP_FAIL;
    rc = ble_gatts_add_svcs(s_services);
    if (rc != 0) return ESP_FAIL;
    ble_hs_cfg.sync_cb = on_sync;
    nimble_port_freertos_init(host_task);
    return ESP_OK;
}
