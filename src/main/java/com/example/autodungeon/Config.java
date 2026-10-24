package com.example.autodungeon;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** All user settings. Saved to .minecraft/config/autodungeon.properties and autodungeon_route.txt */
public final class Config {
    // ---- continuous right-click ----
    public static boolean holdUse = false;
    public static boolean useViaKey = false;     // false: send use-item packets, true: hold the use key
    public static double useInterval = 4.0;      // ticks between right-clicks (20 = 1 second)
    public static boolean useOnlyWhenMobs = false;
    public static double skillRange = 6.0;

    // ---- targeting ----
    public static double scanRange = 12.0;
    public static double approachDist = 2.5;
    public static boolean builtinAttack = false;
    public static boolean autoFace = true;

    // ---- activity area (used when route mode is off) ----
    public static boolean limitArea = true;
    public static double areaRadius = 15.0;
    public static double areaX = 0, areaY = 0, areaZ = 0;

    // ---- route ----
    public static boolean routeMode = true;
    public static boolean routeLoop = true;      // true: loop A-B-C-A..., false: back and forth
    public static double recordSpacing = 3.0;    // blocks between recorded waypoints
    public static double routeReach = 1.5;       // how close counts as "reached a waypoint"
    public static final List<Vec3d> route = new ArrayList<>();

    // ---- looting ----
    public static boolean autoLoot = true;
    public static double lootRange = 8.0;

    // ---- background ----
    public static boolean background = true;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autodungeon.properties");
    }

    private static Path routeFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("autodungeon_route.txt");
    }

    public static void load() {
        Path f = file();
        if (Files.exists(f)) {
            Properties pr = new Properties();
            try (InputStream in = Files.newInputStream(f)) {
                pr.load(in);
                holdUse = b(pr, "holdUse", holdUse);
                useViaKey = b(pr, "useViaKey", useViaKey);
                useInterval = d(pr, "useInterval", useInterval);
                useOnlyWhenMobs = b(pr, "useOnlyWhenMobs", useOnlyWhenMobs);
                skillRange = d(pr, "skillRange", skillRange);
                scanRange = d(pr, "scanRange", scanRange);
                approachDist = d(pr, "approachDist", approachDist);
                builtinAttack = b(pr, "builtinAttack", builtinAttack);
                autoFace = b(pr, "autoFace", autoFace);
                limitArea = b(pr, "limitArea", limitArea);
                areaRadius = d(pr, "areaRadius", areaRadius);
                areaX = d(pr, "areaX", areaX);
                areaY = d(pr, "areaY", areaY);
                areaZ = d(pr, "areaZ", areaZ);
                routeMode = b(pr, "routeMode", routeMode);
                routeLoop = b(pr, "routeLoop", routeLoop);
                recordSpacing = d(pr, "recordSpacing", recordSpacing);
                routeReach = d(pr, "routeReach", routeReach);
                autoLoot = b(pr, "autoLoot", autoLoot);
                lootRange = d(pr, "lootRange", lootRange);
                background = b(pr, "background", background);
            } catch (IOException ignored) {
            }
        }
        loadRoute();
    }

    public static void save() {
        Properties pr = new Properties();
        pr.setProperty("holdUse", String.valueOf(holdUse));
        pr.setProperty("useViaKey", String.valueOf(useViaKey));
        pr.setProperty("useInterval", String.valueOf(useInterval));
        pr.setProperty("useOnlyWhenMobs", String.valueOf(useOnlyWhenMobs));
        pr.setProperty("skillRange", String.valueOf(skillRange));
        pr.setProperty("scanRange", String.valueOf(scanRange));
        pr.setProperty("approachDist", String.valueOf(approachDist));
        pr.setProperty("builtinAttack", String.valueOf(builtinAttack));
        pr.setProperty("autoFace", String.valueOf(autoFace));
        pr.setProperty("limitArea", String.valueOf(limitArea));
        pr.setProperty("areaRadius", String.valueOf(areaRadius));
        pr.setProperty("areaX", String.valueOf(areaX));
        pr.setProperty("areaY", String.valueOf(areaY));
        pr.setProperty("areaZ", String.valueOf(areaZ));
        pr.setProperty("routeMode", String.valueOf(routeMode));
        pr.setProperty("routeLoop", String.valueOf(routeLoop));
        pr.setProperty("recordSpacing", String.valueOf(recordSpacing));
        pr.setProperty("routeReach", String.valueOf(routeReach));
        pr.setProperty("autoLoot", String.valueOf(autoLoot));
        pr.setProperty("lootRange", String.valueOf(lootRange));
        pr.setProperty("background", String.valueOf(background));
        try (OutputStream out = Files.newOutputStream(file())) {
            pr.store(out, "AutoDungeon settings");
        } catch (IOException ignored) {
        }
    }

    public static void saveRoute() {
        List<String> lines = new ArrayList<>();
        for (Vec3d v : route) lines.add(v.x + " " + v.y + " " + v.z);
        try {
            Files.write(routeFile(), lines);
        } catch (IOException ignored) {
        }
    }

    private static void loadRoute() {
        route.clear();
        Path f = routeFile();
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 3) continue;
                try {
                    route.add(new Vec3d(Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2])));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean b(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static double d(Properties p, String k, double def) {
        String v = p.getProperty(k);
        if (v == null) return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
