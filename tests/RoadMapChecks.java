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
        verify(map.needsRefresh(lat + 0.0008, lon, lat, lon), "Movement beyond 75 m fetches coverage");
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
        verify(playback.guidanceAt(cornerDistance).bleLine().equals("S,-1,0") &&
            playback.guidanceAt(playback.getTotalMeters() - 0.1).getMeters() == 1,
            "Passed turns clear and the destination countdown remains positive before arrival");
        verify(playback.guidanceAt(playback.getTotalMeters()).bleLine().equals("S,0,0"),
            "Arrival uses the existing straight packet format with a reserved distance");
        verify(RouteTurn.Companion.decode(RouteTurn.Companion.encode(turns)).equals(turns),
            "Turns survive Activity state and service transport");

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
        for (int i = 0; i < 50; i++) manyRoads.add(new NearbyRoad("road " + i, "primary", longRoad));
        List<String> lines = map.commands(manyRoads, lat, lon);
        verify(lines.get(0).equals("RS,40") && lines.get(lines.size()-1).equals("RF"), "Whole-map transaction brackets 40 roads");
        verify(lines.stream().filter(s -> s.startsWith("RP,")).count() == 3200, "No more than 80 points per road");
        verify(lines.stream().anyMatch(s -> s.equals("RP,0,0,29868300,121544000")), "BLE uses latitude then longitude in microdegrees");
        verify(lines.stream().allMatch(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1 <= 61), "Every command fits the minimum accepted ATT payload");
        verify(lines.size() < 4000, "A maximum road snapshot fits the queue");
        System.out.println("RoadMap: " + checks + " checks passed");
    }
}
