#!/usr/bin/env bash
# Run on the Tencent Cloud Linux host. Existing Valhalla containers are only read.
set -euo pipefail

container=roundnav-valhalla-ningbo-test
work=$(mktemp -d "$HOME/roundnav-uturn-test.XXXXXX")
echo "实验目录: $work"
trap 'echo "实验未完成。生成的文件和构建日志在 $work" >&2' ERR

image=$(sudo docker inspect --format '{{.Image}}' "$container")
git clone --quiet --depth 1 --branch codex/ningbo-junction-connector-test \
  https://github.com/cxboy12/RoundNav_Rebuild.git "$work/source"
export PYTHONPATH="$work/source/tests/valhalla_uturn"

echo '下载公开测试路网'
curl -fsS --compressed --retry 2 --max-time 150 \
  'https://api.openstreetmap.org/api/0.6/map?bbox=121.537,29.861,121.553,29.879' \
  -o "$work/original.osm"

python3 -B - "$work" <<'PY'
import json
import sys
from pathlib import Path
from compare_connector import write_derived_graph

work = Path(sys.argv[1])
link = write_derived_graph(work / "original.osm", work / "experimental.osm")
(work / "link.json").write_text(json.dumps(link))
PY

sudo docker exec "$container" cat /data/ningbo-test-valhalla.json |
  python3 -c '
import json, sys
config = json.load(sys.stdin)
mjolnir = config["mjolnir"]
for key in ("tile_extract", "tile_url", "tile_url_user_pw", "traffic_extract",
            "incident_dir", "incident_log", "admin", "timezone", "landmarks",
            "data_quality_dir", "graph_lua_name", "transit_dir", "transit_feeds_dir"):
    mjolnir.pop(key, None)
mjolnir.update(tile_dir="/data/tiles", concurrency=2)
config["logging"] = {"type": "file", "file_name": "/data/valhalla.log"}
json.dump(config, sys.stdout)
' > "$work/experiment.json"

echo '生成独立测试图'
if command -v osmium >/dev/null; then
  osmium cat "$work/experimental.osm" -o "$work/experimental.osm.pbf"
else
  sudo docker run --rm -v "$work:/work" ubuntu:24.04 sh -ec '
    apt-get update -qq
    apt-get install -y -qq osmium-tool
    osmium cat /work/experimental.osm -o /work/experimental.osm.pbf
  ' > "$work/osmium.log" 2>&1 || { tail -25 "$work/osmium.log"; exit 1; }
fi

sudo docker run --rm --network none -v "$work:/data" \
  --entrypoint valhalla_build_tiles "$image" \
  -c /data/experiment.json /data/experimental.osm.pbf

request='{"locations":[{"lat":29.867300,"lon":121.544409},{"lat":29.873098,"lon":121.547853}],"costing":"motorcycle","units":"kilometers","shape_format":"polyline6"}'
sudo docker run --rm --network none -v "$work:/data" \
  --entrypoint valhalla_service "$image" /data/experiment.json route "$request" \
  > "$work/route.txt"

python3 -B - "$work" <<'PY'
import json
import sys
from pathlib import Path
from compare import decode_polyline
from compare_connector import passes_link

work = Path(sys.argv[1])
trip = json.loads((work / "route.txt").read_text().strip().splitlines()[-1])["trip"]
link = json.loads((work / "link.json").read_text())
used_link = False
print(f"实验路线: {trip['summary']['length']} 公里")
for leg in trip["legs"]:
    points = decode_polyline(leg["shape"])
    used_link |= passes_link(points, link)
    for maneuver in leg["maneuvers"]:
        lon, lat = points[maneuver["begin_shape_index"]]
        print(f"{lat:.6f}, {lon:.6f}  {maneuver['instruction']}")
print("使用上框测试连接:", "是" if used_link else "否")
PY
