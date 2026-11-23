"""Run both costing profiles against an unchanged Ningbo extract and independent fixtures."""

import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

NINGBO = ((29.867300, 121.544409), (29.873098, 121.547853))
FIXTURE = ((29.9086, 121.5000), (29.9088, 121.5000))
PROFILES = ("bicycle", "motor_scooter")


def write_fixture(path, prohibited):
    graph = ET.Element("osm", version="0.6", generator="RoundNav synthetic test")
    points = ((29.91, 121.50), (29.908, 121.50), (29.906, 121.50),
              (29.906, 121.503), (29.908, 121.503))
    for node_id, (lat, lon) in enumerate(points, start=1):
        ET.SubElement(graph, "node", id=str(node_id), version="1", lat=str(lat), lon=str(lon))
    for way_id, nodes, name in ((10, (1, 2), "Test Spine"), (11, (2, 3), "Test South"),
                                (12, (3, 4, 5, 2), "Test Loop")):
        way = ET.SubElement(graph, "way", id=str(way_id), version="1")
        for node_id in nodes:
            ET.SubElement(way, "nd", ref=str(node_id))
        for key, value in (("highway", "residential"), ("name", name), ("surface", "asphalt")):
            ET.SubElement(way, "tag", k=key, v=value)
    if prohibited:
        relation = ET.SubElement(graph, "relation", id="100", version="1")
        for kind, ref, role in (("way", "10", "from"), ("node", "2", "via"), ("way", "10", "to")):
            ET.SubElement(relation, "member", type=kind, ref=ref, role=role)
        ET.SubElement(relation, "tag", k="type", v="restriction")
        ET.SubElement(relation, "tag", k="restriction", v="no_u_turn")
    ET.ElementTree(graph).write(path, encoding="utf-8", xml_declaration=True)


def prepare_graph(root, results, dataset):
    tile_dir = root / f"tiles-{dataset}"
    tile_dir.mkdir()
    config = json.loads(subprocess.check_output(
        ["python3", root / "valhalla/scripts/valhalla_build_config"], text=True
    ))
    config["logging"].update(type="file", color=False, file_name=str(results / "valhalla-build.txt"))
    config["mjolnir"].update(tile_dir=str(tile_dir), concurrency=2,
                             hierarchy=False, shortcuts=False)
    for key in ("tile_extract", "traffic_extract", "admin", "timezone", "landmarks"):
        config["mjolnir"].pop(key, None)
    config_path = root / f"config-{dataset}.json"
    config_path.write_text(json.dumps(config))
    source = results / f"{dataset}.osm"
    if dataset != "ningbo":
        write_fixture(source, prohibited=dataset == "no-uturn")
    pbf = root / f"{dataset}.osm.pbf"
    subprocess.run(["osmium", "cat", source, "-o", pbf], check=True)
    subprocess.run([root / "build/valhalla_build_tiles", "-c", config_path, pbf], check=True)


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


def route(root, results, dataset, mode, build):
    points = NINGBO if dataset == "ningbo" else FIXTURE
    request = {
        "locations": [{"lat": lat, "lon": lon, "type": "break"} for lat, lon in points],
        "costing": mode, "units": "kilometers", "shape_format": "polyline6",
    }
    if dataset != "ningbo":
        for point, heading in zip(request["locations"], (180, 0)):
            point.update(heading=heading, heading_tolerance=30, minimum_reachability=0,
                         search_cutoff=25, node_snap_tolerance=0)
    args = [root / "build/valhalla_service", root / f"config-{dataset}.json", "route",
            json.dumps(request)]
    response = subprocess.check_output(args, text=True, encoding="utf-8")
    # Valhalla can emit informational logging before its one-line response.
    result = json.loads(response.strip().splitlines()[-1])
    leg, = result["trip"]["legs"]
    shape = decode_polyline(leg["shape"])
    instructions = [{
        "type": step["type"],
        "position_lon_lat": shape[step["begin_shape_index"]],
        "instruction": step["instruction"],
    } for step in leg["maneuvers"]]
    name = f"{dataset}-{mode}-{build}"
    summary = {
        "distance_m": round(result["trip"]["summary"]["length"] * 1000),
        "duration_s": result["trip"]["summary"]["time"],
        "shape_points": len(shape),
        "shape_sha256": hashlib.sha256(leg["shape"].encode()).hexdigest(),
        "instructions": instructions,
    }
    (results / f"{name}.geojson").write_text(json.dumps({
        "type": "Feature", "geometry": {"type": "LineString", "coordinates": shape},
        "properties": summary,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    (results / f"{name}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def reverses_at_fixture_junction(summary):
    return any(
        step["type"] in (12, 13)
        and abs(step["position_lon_lat"][0] - 121.5) < 0.00001
        and abs(step["position_lon_lat"][1] - 29.908) < 0.00001
        for step in summary["instructions"]
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    parser.add_argument("build", choices=("baseline", "patched"))
    args = parser.parse_args()
    root = args.root.resolve()
    results = root / "results"
    failures = []
    for dataset in ("ningbo", "ordinary", "no-uturn"):
        if args.build == "baseline":
            prepare_graph(root, results, dataset)
        for mode in PROFILES:
            summary = route(root, results, dataset, mode, args.build)
            print(dataset, mode, args.build, json.dumps(summary, ensure_ascii=False))
            if args.build == "patched" and dataset != "ningbo":
                baseline = json.loads((results / f"{dataset}-{mode}-baseline.json").read_text(encoding="utf-8"))
                if reverses_at_fixture_junction(baseline):
                    failures.append(f"{dataset}/{mode}: baseline selected the ordinary-node reversal")
                if dataset == "no-uturn" and reverses_at_fixture_junction(summary):
                    failures.append(f"{mode}: selected an explicitly prohibited reversal")
                if dataset == "ordinary" and (
                    not reverses_at_fixture_junction(summary)
                    or summary["distance_m"] >= baseline["distance_m"]
                ):
                    failures.append(f"{mode}: patched route did not take the shorter reversal")
    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
