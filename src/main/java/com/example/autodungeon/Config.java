package com.example.autodungeon;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** All user settings. Saved to .minecraft/config/autodungeon.properties */
public final class Config {
    // continuous right-click
    public static boolean holdUse = false;
    public static boolean useOnlyWhenMobs = false;
    public static double skillRange = 6.0;      // "only when mobs near" distance

    // targeting
    public static double scanRange = 12.0;      // monster search range
    public static double approachDist = 2.5;    // stop this far from the monster
    public static boolean builtinAttack = false; // off by default: use Wurst KillAura
    public static boolean autoFace = true;      // turn the camera while moving / facing target

    // activity area (cylinder around a centre point)
    public static boolean limitArea = true;
    public static double areaRadius = 15.0;
    public static double areaX = 0, areaY = 0, areaZ = 0;

    // looting
    public static boolean autoLoot = true;
    public static double lootRange = 8.0;

    // keep running when the game window is not focused (alt-tab)
    public static boolean background = true;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autodungeon.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        Properties pr = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            pr.load(in);
        } catch (IOException e) {
            return;
        }
        holdUse = b(pr, "holdUse", holdUse);
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
        autoLoot = b(pr, "autoLoot", autoLoot);
        lootRange = d(pr, "lootRange", lootRange);
        background = b(pr, "background", background);
    }

    public static void save() {
        Properties pr = new Properties();
        pr.setProperty("holdUse", String.valueOf(holdUse));
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
        pr.setProperty("autoLoot", String.valueOf(autoLoot));
        pr.setProperty("lootRange", String.valueOf(lootRange));
        pr.setProperty("background", String.valueOf(background));
        try (OutputStream out = Files.newOutputStream(file())) {
            pr.store(out, "AutoDungeon settings");
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
