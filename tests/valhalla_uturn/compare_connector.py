"""Evaluate a hypothetical motor-vehicle link in a disposable Ningbo graph."""

import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from compare import NINGBO, prepare_graph, route

SOUTHBOUND = "8117742122"
NORTHBOUND = "8117742123"
CASE_POINTS = {
    "reproduction": NINGBO,
    "junction": ((29.867817, 121.5445852), (29.8678089, 121.5447379)),
    "roundnav-preview": ((29.868300, 121.544000), (29.875000, 121.550000)),
}


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


def main():
    root = Path(sys.argv[1]).resolve()
    results = root / "results"
    original = results / "ningbo.osm"
    source_hash = hashlib.sha256(original.read_bytes()).hexdigest()
    derived = root / "ningbo-experimental.osm"
    link = write_derived_graph(original, derived)
    failures = []
    summaries = []
    for variant, source in (("original", original), ("experimental", derived)):
        dataset = f"ningbo-{variant}"
        prepare_graph(root, results, dataset, source=source)
        for name, points in CASE_POINTS.items():
            locations = [{"lat": lat, "lon": lon, "type": "break"} for lat, lon in points]
            if name == "junction":
                for location, heading in zip(locations, (180, 0)):
                    location.update(heading=heading, heading_tolerance=30)
            for mode in ("motorcycle", "auto", "bicycle"):
                summary = route(root, results, dataset, mode, name, locations=locations)
                geojson = results / f"{dataset}-{mode}-{name}.geojson"
                coordinates = json.loads(geojson.read_text(encoding="utf-8"))["geometry"]["coordinates"]
                summary["uses_test_link"] = passes_link(coordinates, link)
                print(name, mode, variant, json.dumps(summary, ensure_ascii=False))
                summaries.append(dict(summary, case=name, mode=mode, variant=variant))
                should_use = variant == "experimental" and mode in ("motorcycle", "auto")
                if summary["uses_test_link"] != should_use:
                    failures.append(f"{name}/{mode}/{variant}: unexpected test link use")
    if hashlib.sha256(original.read_bytes()).hexdigest() != source_hash:
        failures.append("The original OSM input was changed")
    (results / "connector-comparison.json").write_text(
        json.dumps({"source_sha256": source_hash, "results": summaries}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
