package com.example.roundnav;

import java.util.*;
import kotlin.Pair;

/** Runs against the Kotlin classes built by :app:assembleDebug, with no Android runtime. */
public final class RoadMapChecks {
    private static final RoadMap map = RoadMap.INSTANCE;
    private static int checks;
    private static void verify(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    private static NearbyRoad road(String name, double... coordinates) {
        List<Pair<Double, Double>> points = new ArrayList<>();
        for (int i = 0; i < coordinates.length; i += 2) points.add(new Pair<>(coordinates[i], coordinates[i + 1]));
        return new NearbyRoad(name, "primary", points);
    }
    public static void main(String[] args) {
        double lat = 29.8683, lon = 121.5440;
        verify(map.simulationOffsetMeters(0) == 0 && map.simulationOffsetMeters(54) == 270,
            "Indoor simulation advances north to 270 m");
        verify(map.simulationOffsetMeters(108) == 0 && map.simulationOffsetMeters(109) == 5,
            "Indoor simulation returns continuously and repeats");
        verify(map.simulationBearing(53) == 0 && map.simulationBearing(54) == 180,
            "Simulation heading changes at the turnaround");
        for (long i = 1; i <= 216; i++) {
            if (Math.abs(map.simulationOffsetMeters(i) - map.simulationOffsetMeters(i - 1)) != 5)
                throw new AssertionError("Simulation jumps or stalls at step " + i);
        }
        verify(true, "Simulation stays continuous through two full loops");
        verify(map.needsRefresh(lat, lon, null, null), "First location loads roads");
        verify(!map.needsRefresh(lat + 0.0001, lon, lat, lon), "Small movements pan without fetching");
        verify(map.needsRefresh(lat + 0.00045, lon, lat, lon), "Movement beyond 45 m fetches coverage");
        verify(Math.abs(map.distanceMeters(lat, lon, lat + 0.001, lon) - 111.31949) < 0.01, "Latitude distance is in metres");

        double north = lat + .001;
        double east = lon + .001;
        List<RouteTurn> turns = List.of(new RouteTurn(2, TurnDirection.RIGHT));
        RoutePlayback playback = new RoutePlayback(
            road("route", lon, lat, lon, lat, lon, north, east, north).getPoints(), turns);
        double cornerDistance = map.distanceMeters(lat, lon, north, lon);
        PlaybackPoint start = playback.pointAt(-5);
        verify(start.getLatitude() == lat && start.getLongitude() == lon && start.getBearing() == 0,
            "Playback starts at the first route point after skipping a duplicate");
        PlaybackPoint beforeTurn = playback.pointAt(cornerDistance / 2);
        verify(Math.abs(beforeTurn.getLatitude() - (lat + north) / 2) < 1e-10 &&
            beforeTurn.getLongitude() == lon && beforeTurn.getBearing() == 0,
            "Playback advances by metres along the first road");
        PlaybackPoint turn = playback.pointAt(cornerDistance);
        PlaybackPoint afterTurn = playback.pointAt((cornerDistance + playback.getTotalMeters()) / 2);
        verify(turn.getLatitude() == north && turn.getLongitude() == lon && turn.getBearing() == 90 &&
            afterTurn.getLatitude() == north && Math.abs(afterTurn.getLongitude() - (lon + east) / 2) < 1e-10,
            "Playback follows the route around a corner and updates its heading");
        PlaybackPoint end = playback.pointAt(playback.getTotalMeters() + 15);
        verify(end.getLatitude() == north && end.getLongitude() == east && end.getBearing() == 90,
            "Playback stops at the last route point even when a step overshoots");
        verify(new RoutePlayback(road("same point", lon, lat, lon, lat).getPoints(), List.of()).getTotalMeters() == 0,
            "A route with no movement has zero playback length");
        verify(playback.guidanceAt(0).bleLine().equals("R,112,0") &&
            playback.guidanceAt(cornerDistance - 0.1).bleLine().equals("R,1,0"),
            "Turn countdown uses the shape index even if the route contains duplicate points");
        verify(playback.guidanceAt(cornerDistance).getDirection() == null &&
            playback.guidanceAt(playback.getTotalMeters() - 0.1).bleLine().equals("S,1,0"),
            "Passing the final turn shows straight with the distance to the destination");
        verify(playback.guidanceAt(playback.getTotalMeters()).bleLine().equals("S,0,0"),
            "Arrival uses the existing straight packet format with a reserved distance");
        verify(RouteTurn.Companion.decode(RouteTurn.Companion.encode(turns)).equals(turns),
            "Turns survive Activity state and service transport");

        double secondCorner = lon + .008;
        RoutePlayback twoTurns = new RoutePlayback(
            road("two turns", lon, lat, lon, north, secondCorner, north, secondCorner, north + .001).getPoints(),
            List.of(new RouteTurn(1, TurnDirection.RIGHT), new RouteTurn(2, TurnDirection.LEFT)));
        double secondTurnDistance = cornerDistance + map.distanceMeters(north, lon, north, secondCorner);
        verify(twoTurns.guidanceAt(cornerDistance).bleLine().startsWith("S,") &&
            twoTurns.guidanceAt(cornerDistance).getMeters() > 500,
            "Passing a turn restores straight when the next turn is still far away");
        verify(twoTurns.guidanceAt(secondTurnDistance - 500.1).bleLine().equals("S,501,0"),
            "A turn just outside the notice distance remains straight");
        verify(twoTurns.guidanceAt(secondTurnDistance - 500.0).bleLine().equals("L,500,0") &&
            twoTurns.guidanceAt(secondTurnDistance - 1).bleLine().equals("L,1,0"),
            "Turn guidance begins at 500 metres and continues through the approach");

        List<Pair<Double, Double>> guidanceShape = new ArrayList<>();
        for (int i = 0; i < 1200; i++) guidanceShape.add(new Pair<>(lon + i * .00001, lat));
        List<RouteTurn> longTurns = List.of(new RouteTurn(221, TurnDirection.LEFT), new RouteTurn(926, TurnDirection.U_TURN));
        RouteResult sampled = RouteApi.INSTANCE.displayRoute(new RouteResult(guidanceShape, null, null, longTurns));
        verify(sampled.getCoordinates().size() == 500 &&
            sampled.getCoordinates().get(0).equals(guidanceShape.get(0)) &&
            sampled.getCoordinates().get(499).equals(guidanceShape.get(1199)), "BLE capacity and both endpoints survive sampling");
        for (int i = 0; i < longTurns.size(); i++) {
            RouteTurn before = longTurns.get(i);
            RouteTurn after = sampled.getTurns().get(i);
            verify(after.getDirection() == before.getDirection() &&
                sampled.getCoordinates().get(after.getShapeIndex()).equals(guidanceShape.get(before.getShapeIndex())),
                "A sampled route keeps the original turn coordinate and remaps its index");
        }
        List<RouteTraffic> traffic = new ArrayList<>(java.util.Collections.nCopies(1199, RouteTraffic.CLEAR));
        traffic.set(555, RouteTraffic.BUSY);
        RouteResult trafficRoute = RouteApi.INSTANCE.displayRoute(new RouteResult(guidanceShape, null, null, longTurns, traffic));
        int busy = trafficRoute.getCoordinates().indexOf(guidanceShape.get(555));
        verify(busy >= 0 && trafficRoute.getTraffic().size() == trafficRoute.getCoordinates().size() - 1 &&
            trafficRoute.getCoordinates().get(busy + 1).equals(guidanceShape.get(556)) &&
            trafficRoute.getTraffic().get(busy) == RouteTraffic.BUSY,
            "Route sampling preserves both vertices of a short congested edge");
        verify(RouteApi.INSTANCE.toBleCommands(trafficRoute).get(busy + 1).endsWith(",3"),
            "Traffic is appended to the route point that starts the colored segment");

        NearbyRoad crossing = road("crossing", lon - .003, lat, lon + .003, lat);
        NearbyRoad nearby = road("nearby", lon + .001, lat, lon + .001, lat + .001);
        List<NearbyRoad> selected = map.selectRoads(Arrays.asList(nearby, crossing), lat, lon);
        verify(selected.get(0) == crossing, "Choose by segment distance, including roads crossing the viewport");

        List<Pair<Double, Double>> longRoad = new ArrayList<>();
        for (int i = 0; i <= 200; i++) longRoad.add(new Pair<>(lon + i * .00001, lat + (i == 100 ? .002 : 0)));
        List<Pair<Double, Double>> simplified = map.simplify(longRoad, 80);
        verify(simplified.size() == 80 && simplified.get(0).equals(longRoad.get(0)) &&
            simplified.get(79).equals(longRoad.get(200)), "Simplification respects capacity and endpoints");
        verify(simplified.contains(longRoad.get(100)), "Simplification preserves a sharp corner");

        List<NearbyRoad> manyRoads = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            List<Pair<Double, Double>> street = new ArrayList<>();
            for (int j = 0; j <= 200; j++) street.add(new Pair<>(lon - .001 + j * .00001, lat + (i - 40) * .00003 + (j == 100 ? .0003 : 0)));
            manyRoads.add(new NearbyRoad("road " + i, i % 2 == 0 ? "primary" : "minor", street));
        }
        List<String> lines = map.commands(manyRoads, lat, lon, null);
        int shapes = Integer.parseInt(lines.get(0).substring(3));
        verify(lines.get(0).startsWith("RS,") && shapes == map.MAX_STREET_ENTRIES && lines.get(lines.size() - 1).equals("RF"),
            "Dense snapshots fill the street budget");
        verify(lines.get(1).equals("RC,29868300,121544000"), "BLE centre uses latitude then longitude in microdegrees");
        verify(lines.stream().filter(s -> s.startsWith("RL,")).count() == shapes, "Each street piece is one compact command");
        verify(lines.stream().allMatch(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1 <= 61),
            "Every command fits the minimum accepted ATT payload");
        verify(map.commands(manyRoads, lat, lon, null, 240).stream()
                .allMatch(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1 <= 240),
            "Every command fits the negotiated ATT payload");
        boolean decodedClose = true;
        int maxPoints = 0;
        for (String line : lines) {
            if (!line.startsWith("RL,")) continue;
            List<Pair<Double, Double>> decoded = map.decodeShape(line, 29868300, 121544000);
            maxPoints = Math.max(maxPoints, decoded.size());
            for (Pair<Double, Double> p : decoded) {
                double best = Double.MAX_VALUE;
                for (NearbyRoad r : manyRoads) for (Pair<Double, Double> q : r.getPoints())
                    best = Math.min(best, map.distanceMeters(p.getSecond(), p.getFirst(), q.getSecond(), q.getFirst()));
                if (best > 1.0) decodedClose = false;
            }
        }
        verify(decodedClose, "Compact metre offsets decode within 1 m of the source geometry");
        verify(maxPoints >= 2 && maxPoints <= 24, "Street pieces stay within the firmware point capacity");
        List<String> ordered = map.commands(Arrays.asList(
            new NearbyRoad("", "primary", road("", lon, lat, lon + .001, lat).getPoints()),
            new NearbyRoad("", "minor", road("", lon, lat, lon, lat + .001).getPoints())), lat, lon, null);
        verify(ordered.get(0).equals("RS,2") && ordered.get(2).startsWith("RL,0,4,") && ordered.get(3).startsWith("RL,1,2,"),
            "Local streets are drawn before major roads");

        List<NearbyRoad> mixed = new ArrayList<>();
        mixed.add(new NearbyRoad("", "path", road("", lon, lat, lon + .001, lat).getPoints()));
        mixed.add(new NearbyRoad("", "service", road("", lon, lat, lon, lat + .001).getPoints()));
        mixed.add(new NearbyRoad("far", "primary", road("", lon + .01, lat, lon + .011, lat).getPoints()));
        for (int i = 0; i < 120; i++) {
            double bx = lon + (i % 12) * .00015, by = lat + (i / 12) * .00015;
            mixed.add(new NearbyRoad("", "building",
                road("", bx, by, bx + .0001, by, bx + .0001, by + .0001, bx, by + .0001, bx, by).getPoints()));
        }
        mixed.add(new NearbyRoad("", "building", road("", lon, lat, lon + .00001, lat, lon + .00001, lat + .00001, lon, lat + .00001, lon, lat).getPoints()));
        List<String> detailed = map.commands(mixed, lat, lon, null);
        long buildingLines = detailed.stream().filter(s -> s.startsWith("RQ,")).count();
        verify(buildingLines == map.MAX_BUILDINGS && detailed.get(2).startsWith("RQ,0,"),
            "Buildings come first and fill their own budget");
        List<Pair<Double, Double>> corners = map.decodeShape(detailed.get(2), 29868300, 121544000);
        verify(corners.size() == 4, "A closed rectangular footprint is sent as four corners");
        verify(map.distanceMeters(corners.get(0).getSecond(), corners.get(0).getFirst(),
                corners.get(2).getSecond(), corners.get(2).getFirst()) > 5,
            "Footprints too small to see are skipped");
        verify(detailed.stream().anyMatch(s -> s.startsWith("RL,") && s.split(",")[2].equals("6")) &&
            detailed.stream().filter(s -> s.startsWith("RL,")).count() == 1,
            "Alleys use the service class; footpaths and geometry beyond the screen are dropped");
        verify(Integer.parseInt(detailed.get(0).substring(3)) <= map.MAX_ENTRIES, "A snapshot fits the firmware road store");
        double matched = playback.match(north, lon + .0004, cornerDistance, 10.0);
        verify(matched > cornerDistance && playback.match(north, lon + .00035, matched, 10.0) == matched,
            "GPS matching advances after the turn and does not regress with jitter");
        verify(playback.match(north + .001, lon, matched, 10.0) == null,
            "An off-route fix has no turn or route progress");
        verify(playback.nearestVertex(cornerDistance + 3) == 2,
            "A turn distance is anchored to the matching vertex of the expanded shape");
        System.out.println("RoadMap: " + checks + " checks passed");
    }
}
