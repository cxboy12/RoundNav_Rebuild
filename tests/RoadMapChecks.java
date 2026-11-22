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
