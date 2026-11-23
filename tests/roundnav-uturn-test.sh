#!/usr/bin/env bash
# Run on the Tencent Cloud Linux host. Existing Valhalla containers are only read.
set -euo pipefail

container=roundnav-valhalla-ningbo-test
work=$(mktemp -d "$HOME/roundnav-uturn-test.XXXXXX")
echo "实验目录: $work"
trap 'echo "实验未完成。生成的文件和构建日志在 $work" >&2' ERR

image=$(sudo docker inspect --format '{{.Image}}' "$container")
cat > "$work/roundnav_fixture.py" <<'HELPER'
import xml.etree.ElementTree as ET


SOUTHBOUND = "8117742122"
NORTHBOUND = "8117742123"


def tags(element):
    return {tag.get("k"): tag.get("v") for tag in element.findall("tag")}


def write_derived_graph(original, destination):
    tree = ET.parse(original)
    graph = tree.getroot()
    nodes = {node.get("id"): node for node in graph.findall("node")}
    ways = {way.get("id"): way for way in graph.findall("way")}
    endpoints = {SOUTHBOUND, NORTHBOUND}
    footway = ways["871536802"]
    if tags(footway).get("highway") != "footway" or not endpoints.issubset(
        {node.get("ref") for node in footway.findall("nd")}
    ):
        raise RuntimeError("The public junction topology has changed; inspect it before testing")
    for way_id, node_id in (("62125946", SOUTHBOUND), ("909234228", NORTHBOUND)):
        way = ways[way_id]
        if tags(way).get("oneway") != "yes" or not any(
            node.get("ref") == node_id for node in way.findall("nd")
        ):
            raise RuntimeError(f"One-way carriageway {way_id} no longer matches the fixture")
    for relation in graph.findall("relation"):
        if tags(relation).get("type", "").startswith("restriction") and any(
            (member.get("type") == "node" and member.get("ref") in endpoints)
            or (member.get("type") == "way" and member.get("ref") == "871536802")
            for member in relation.findall("member")
        ):
            raise RuntimeError(f"Review turn restriction {relation.get('id')} before testing")

    # The existing sidewalk and all restrictions remain intact in the derived file.
    way_id = max(map(int, ways)) + 1
    link = ET.Element("way", id=str(way_id), version="1")
    for node_id in (SOUTHBOUND, NORTHBOUND):
        ET.SubElement(link, "nd", ref=node_id)
    for key, value in (
        ("highway", "primary_link"), ("oneway", "yes"), ("access", "no"),
        ("motorcar", "yes"), ("motorcycle", "yes"),
    ):
        ET.SubElement(link, "tag", k=key, v=value)
    insertion = list(graph).index(next(reversed(ways.values()))) + 1
    graph.insert(insertion, link)
    tree.write(destination, encoding="utf-8", xml_declaration=True)

    return [
        (float(nodes[node_id].get("lon")), float(nodes[node_id].get("lat")))
        for node_id in (SOUTHBOUND, NORTHBOUND)
    ]


def passes_link(points, link):
    def near(point, endpoint):
        return max(abs(value - expected) for value, expected in zip(point, endpoint)) < 0.000002

    return any(near(start, link[0]) and near(end, link[1])
               for start, end in zip(points, points[1:]))


def decode_polyline(encoded):
    values = iter(encoded)

    def delta():
        result = shift = 0
        for char in values:
            value = ord(char) - 63
            result |= (value & 31) << shift
            if value < 32:
                return ~(result >> 1) if result & 1 else result >> 1
            shift += 5
        return None

    lat = lon = 0
    points = []
    while (latitude := delta()) is not None:
        lat += latitude
        lon += delta()
        points.append((lon / 1e6, lat / 1e6))
    return points
HELPER
export PYTHONPATH="$work"

if [[ $# -gt 0 ]]; then
  cp -- "$1" "$work/original.osm"
else
  echo '下载公开测试路网'
  curl -fsS --compressed --retry 2 --max-time 150 \
    'https://api.openstreetmap.org/api/0.6/map?bbox=121.537,29.861,121.553,29.879' \
    -o "$work/original.osm"
fi

python3 -B - "$work" <<'PY'
import json
import sys
from pathlib import Path
from roundnav_fixture import write_derived_graph

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
from roundnav_fixture import decode_polyline, passes_link

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
