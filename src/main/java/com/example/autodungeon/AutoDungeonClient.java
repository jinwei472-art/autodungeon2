package com.example.autodungeon;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * AutoDungeon v1.3 (Minecraft 1.21.11, Fabric/Yarn)
 *  - Record your own route (H), then auto mode (G) walks it again and again.
 *  - On the way: monsters are fought (Wurst KillAura or built-in), items are picked up.
 *  - A* path-finding around walls between waypoints.
 *  - Continuous right-click (J), settings GUI (K), background running.
 */
public class AutoDungeonClient implements ClientModInitializer {

    static AutoDungeonClient instance;

    // ---------- fixed tuning ----------
    private static final float TURN_SPEED = 30.0F;
    private static final int HUD_INTERVAL = 10;
    private static final int REPATH_TICKS = 10;
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_EXPANSIONS = 2500;
    private static final double AREA_HEIGHT = 10.0;
    private static final double RETURN_DIST = 3.0;
    private static final double MELEE_REACH = 3.0;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final int[] DY_ALL = {0, 1, -1, -2, -3};
    private static final int[] DY_FLAT = {0};

    private static KeyBinding toggleKey, guiKey, holdKey, recKey;

    private boolean enabled = false;
    private boolean recording = false;
    private boolean useHeld = false;
    private int useTimer = 0;
    private int tickCount = 0;
    private int hudTimer = 0;

    // background mode
    private boolean pauseSaved = false;
    private boolean savedPause = true;

    // route following
    private int routeIdx = 0;
    private int routeDir = 1;
    private boolean blind = false;
    private int blindUntil = 0;
    private int routeWaitUntil = 0;
    private int routeFailStreak = 0;
    private boolean skipWaypoint = false;

    // path state
    private List<BlockPos> path = null;
    private int pathIdx = 0;
    private BlockPos pathGoal = null;
    private int repathTimer = 0;
    private int navEntityId = -1;
    private int centerFailUntil = 0;

    // anti-stuck
    private int stuckTimer = 0;
    private int stuckStreak = 0;
    private int unstickTicks = 0;
    private boolean strafeRight = true;
    private boolean movedThisWindow = false;
    private Vec3d checkpoint = null;

    private final Map<Integer, Integer> blacklist = new HashMap<>();

    private static final class Node {
        final int x, y, z;
        double g = Double.MAX_VALUE, f = 0;
        Node parent;
        boolean closed;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    boolean isEnabled() { return enabled; }
    boolean isRecording() { return recording; }

    @Override
    public void onInitializeClient() {
        instance = this;
        Config.load();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("autodungeon", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autodungeon.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, cat));
        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autodungeon.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        holdKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autodungeon.hold_use", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));
        recKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autodungeon.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, cat));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // =====================================================================
    //  Switches
    // =====================================================================
    void setEnabled(MinecraftClient mc, boolean v) {
        if (v && recording) setRecording(mc, false);
        enabled = v;
        path = null;
        blacklist.clear();
        checkpoint = null;
        unstickTicks = 0;
        stuckStreak = 0;
        blind = false;
        skipWaypoint = false;
        routeFailStreak = 0;
        routeWaitUntil = 0;
        if (v) {
            ClientPlayerEntity p = mc.player;
            if (p != null && Config.routeMode && !Config.route.isEmpty()) {
                routeIdx = nearestWaypoint(posOf(p));
                routeDir = 1;
                chat(mc, "[AutoDungeon] Enabled  路线模式 " + Config.route.size() + " 个路点,从第 " + (routeIdx + 1) + " 个开始");
            } else if (p != null && Config.limitArea) {
                Config.areaX = p.getX();
                Config.areaY = p.getY();
                Config.areaZ = p.getZ();
                Config.save();
                chat(mc, String.format("[AutoDungeon] Enabled  中心 (%.0f, %.0f, %.0f) 半径 %.0f",
                        Config.areaX, Config.areaY, Config.areaZ, Config.areaRadius));
            } else {
                chat(mc, "[AutoDungeon] Enabled");
            }
        } else {
            releaseMovement(mc);
            chat(mc, "[AutoDungeon] Disabled");
        }
    }

    void setRecording(MinecraftClient mc, boolean v) {
        if (v == recording) return;
        if (v) {
            if (enabled) setEnabled(mc, false);
            Config.route.clear();
            recording = true;
            chat(mc, "[AutoDungeon] 开始录制路线:请沿着路线走,走完再按 H 结束");
        } else {
            recording = false;
            Config.saveRoute();
            chat(mc, "[AutoDungeon] 路线已保存,共 " + Config.route.size() + " 个路点");
        }
    }

    private int nearestWaypoint(Vec3d from) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < Config.route.size(); i++) {
            double d = Config.route.get(i).squaredDistanceTo(from);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // =====================================================================
    //  Main tick
    // =====================================================================
    private void onTick(MinecraftClient mc) {
        tickCount++;
        applyBackground(mc);

        while (toggleKey.wasPressed()) setEnabled(mc, !enabled);
        while (recKey.wasPressed()) setRecording(mc, !recording);
        while (holdKey.wasPressed()) {
            Config.holdUse = !Config.holdUse;
            Config.save();
            chat(mc, "[AutoDungeon] 持续右键 " + (Config.holdUse ? "开启" : "关闭"));
        }
        while (guiKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new ConfigScreen());
        }

        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
            useHeld = false;
            return;
        }

        // Backup for background mode: if the pause menu still pops up, close it again.
        if (Config.background && (enabled || Config.holdUse || recording) && !mc.isWindowFocused()
                && mc.currentScreen instanceof GameMenuScreen) {
            mc.setScreen(null);
        }

        updateHoldUse(mc, p);

        if (recording) {
            if (mc.currentScreen == null) recordStep(p);
            return;
        }

        if (!enabled) return;
        if (!p.isAlive()) {
            setEnabled(mc, false);
            return;
        }
        if (mc.currentScreen != null) {
            releaseMovement(mc);
            return;
        }

        trackStuck(p);

        // ---- 1) monsters ----
        MobEntity mob = findMob(mc, p);
        if (mob != null) {
            double dist = p.distanceTo(mob);
            hud(p, String.format("目标 %s  %.1f格  (%.0f, %.0f, %.0f)",
                    mob.getName().getString(), dist, mob.getX(), mob.getY(), mob.getZ()));
            if (Config.builtinAttack && dist <= MELEE_REACH && p.getAttackCooldownProgress(0.5F) >= 1.0F) {
                mc.interactionManager.attackEntity(p, mob);
                p.swingHand(Hand.MAIN_HAND);
            }
            if (dist <= Config.approachDist) {
                releaseMovement(mc);
                if (Config.autoFace) face(p, posOf(mob).add(0, mob.getHeight() * 0.5, 0));
            } else if (!navigate(mc, p, blockOf(mob), posOf(mob), mob.getId())) {
                blacklist.put(mob.getId(), tickCount + 100);
            }
            return;
        }

        // ---- 2) dropped items ----
        if (Config.autoLoot) {
            ItemEntity item = findItem(mc, p);
            if (item != null) {
                hud(p, "捡取 " + item.getStack().getName().getString());
                if (!navigate(mc, p, blockOf(item), posOf(item), item.getId())) {
                    blacklist.put(item.getId(), tickCount + 100);
                }
                return;
            }
        }

        // ---- 3) follow the recorded route ----
        if (Config.routeMode && !Config.route.isEmpty()) {
            followRoute(mc, p);
            return;
        }

        // ---- 4) (no route) return to the centre of the area ----
        if (Config.limitArea && tickCount >= centerFailUntil) {
            Vec3d c = new Vec3d(Config.areaX, Config.areaY, Config.areaZ);
            if (hdist(posOf(p), c) > RETURN_DIST) {
                hud(p, "返回中心");
                BlockPos cb = new BlockPos(MathHelper.floor(c.x), MathHelper.floor(c.y), MathHelper.floor(c.z));
                if (!navigate(mc, p, cb, c, -1)) {
                    centerFailUntil = tickCount + 40;
                    releaseMovement(mc);
                }
                return;
            }
        }

        releaseMovement(mc);
        hud(p, Config.routeMode ? "未录制路线,按 H 录制" : "等待目标...");
    }

    // =====================================================================
    //  Background mode
    // =====================================================================
    /** Turn off "pause on lost focus" while the bot is active, restore it afterwards. */
    private void applyBackground(MinecraftClient mc) {
        boolean need = Config.background && (enabled || Config.holdUse || recording);
        if (need) {
            if (!pauseSaved) {
                savedPause = mc.options.pauseOnLostFocus;
                pauseSaved = true;
            }
            mc.options.pauseOnLostFocus = false;
        } else if (pauseSaved) {
            mc.options.pauseOnLostFocus = savedPause;
            pauseSaved = false;
        }
    }

    // =====================================================================
    //  Continuous right-click (works even when auto mode is off)
    // =====================================================================
    private void updateHoldUse(MinecraftClient mc, ClientPlayerEntity p) {
        boolean active = Config.holdUse && p.isAlive() && mc.currentScreen == null
                && (!Config.useOnlyWhenMobs || hasMobWithin(mc, p, Config.skillRange));

        if (Config.useViaKey) {                       // mode A: hold the use key
            if (active) {
                mc.options.useKey.setPressed(true);
                useHeld = true;
            } else if (useHeld) {
                mc.options.useKey.setPressed(false);
                useHeld = false;
            }
            return;
        }

        if (useHeld) {                                // switched away from key mode
            mc.options.useKey.setPressed(false);
            useHeld = false;
        }
        if (!active) {                                // mode B: send use-item every N ticks
            useTimer = 0;
            return;
        }
        if (--useTimer > 0) return;
        useTimer = Math.max(1, (int) Math.round(Config.useInterval));
        if (p.getMainHandStack().isEmpty() || p.isUsingItem()) return;
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private boolean hasMobWithin(MinecraftClient mc, ClientPlayerEntity p, double r) {
        return !mc.world.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(r),
                e -> e instanceof Monster && e.isAlive() && p.distanceTo(e) <= r).isEmpty();
    }

    // =====================================================================
    //  Route recording / following
    // =====================================================================
    private void recordStep(ClientPlayerEntity p) {
        Vec3d now = posOf(p);
        List<Vec3d> r = Config.route;
        if (p.isOnGround()) {
            if (r.isEmpty()) {
                r.add(now);
            } else {
                Vec3d last = r.get(r.size() - 1);
                if (hdist(now, last) >= Config.recordSpacing || Math.abs(now.y - last.y) >= 1.5) r.add(now);
            }
        }
        hud(p, "录制中...  路点 " + r.size());
    }

    private boolean reached(ClientPlayerEntity p, Vec3d wp) {
        return hdist(posOf(p), wp) <= Config.routeReach && Math.abs(p.getY() - wp.y) <= 3.0;
    }

    private void advanceWaypoint() {
        int n = Config.route.size();
        if (n == 0) return;
        if (Config.routeLoop) {
            routeIdx = (routeIdx + 1) % n;
        } else {
            int next = routeIdx + routeDir;
            if (next < 0 || next >= n) {
                routeDir = -routeDir;
                next = routeIdx + routeDir;
            }
            routeIdx = MathHelper.clamp(next, 0, n - 1);
        }
        path = null;
        blind = false;
    }

    private void followRoute(MinecraftClient mc, ClientPlayerEntity p) {
        List<Vec3d> r = Config.route;
        int n = r.size();
        if (routeIdx >= n) routeIdx = 0;

        if (tickCount < routeWaitUntil) {
            releaseMovement(mc);
            return;
        }
        if (skipWaypoint) {
            skipWaypoint = false;
            advanceWaypoint();
        }

        Vec3d wp = r.get(routeIdx);
        if (reached(p, wp)) {
            routeFailStreak = 0;
            advanceWaypoint();
            wp = r.get(routeIdx);
        }
        hud(p, String.format("路线 %d/%d  距离 %.1f", routeIdx + 1, n, hdist(posOf(p), wp)));

        if (blind) {
            if (tickCount < blindUntil) {                 // walk straight at it for a short while
                movedThisWindow = true;
                navEntityId = -1;
                moveToward(mc, p, wp, wp.y > p.getY() + 0.6);
                return;
            }
            advanceWaypoint();                            // could not get there: skip this waypoint
            if (++routeFailStreak >= n) {
                routeFailStreak = 0;
                routeWaitUntil = tickCount + 60;
            }
            return;
        }

        BlockPos wb = new BlockPos(MathHelper.floor(wp.x), MathHelper.floor(wp.y), MathHelper.floor(wp.z));
        if (!navigate(mc, p, wb, wp, -1)) {
            blind = true;                                 // A* found no way: try walking straight
            blindUntil = tickCount + 30;
            path = null;
        }
    }

    // =====================================================================
    //  Target selection
    // =====================================================================
    private MobEntity findMob(MinecraftClient mc, ClientPlayerEntity p) {
        double r = Config.scanRange;
        List<MobEntity> list = mc.world.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(r),
                e -> e instanceof Monster && e.isAlive() && p.distanceTo(e) <= r
                        && !isBlacklisted(e) && inArea(posOf(e)));
        return list.stream().min(Comparator.comparingDouble(p::squaredDistanceTo)).orElse(null);
    }

    private ItemEntity findItem(MinecraftClient mc, ClientPlayerEntity p) {
        double r = Config.lootRange;
        List<ItemEntity> list = mc.world.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(r),
                e -> e.isAlive() && p.distanceTo(e) <= r && !isBlacklisted(e) && inArea(posOf(e)));
        return list.stream().min(Comparator.comparingDouble(p::squaredDistanceTo)).orElse(null);
    }

    private boolean inArea(Vec3d v) {
        if (!Config.limitArea) return true;
        if (Config.routeMode && !Config.route.isEmpty()) return true;   // route mode: no area limit
        double dx = v.x - Config.areaX;
        double dz = v.z - Config.areaZ;
        return Math.sqrt(dx * dx + dz * dz) <= Config.areaRadius
                && Math.abs(v.y - Config.areaY) <= AREA_HEIGHT;
    }

    private boolean isBlacklisted(Entity e) {
        Integer t = blacklist.get(e.getId());
        return t != null && t > tickCount;
    }

    // =====================================================================
    //  Navigation (A* + waypoint following)
    // =====================================================================
    /** Walk to a goal using a path around obstacles. Returns false if no way forward exists. */
    private boolean navigate(MinecraftClient mc, ClientPlayerEntity p, BlockPos goal, Vec3d dest, int entityId) {
        movedThisWindow = true;
        navEntityId = entityId;

        boolean need = path == null || pathGoal == null || --repathTimer <= 0
                || pathGoal.getManhattanDistance(goal) > 2;
        if (need) {
            repathTimer = REPATH_TICKS;
            path = findPath(mc.world, blockOf(p), goal);
            pathIdx = 0;
            pathGoal = goal;
            if (path == null) return false;
        }

        while (pathIdx < path.size()) {
            BlockPos wp = path.get(pathIdx);
            double dx = wp.getX() + 0.5 - p.getX();
            double dz = wp.getZ() + 0.5 - p.getZ();
            double dy = p.getY() - wp.getY();
            if (dx * dx + dz * dz < 0.3 && dy > -1.3 && dy < 3.5) pathIdx++;
            else break;
        }

        if (pathIdx >= path.size()) {
            moveToward(mc, p, dest, false);
        } else {
            BlockPos wp = path.get(pathIdx);
            moveToward(mc, p, new Vec3d(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5),
                    wp.getY() > p.getBlockY());
        }
        return true;
    }

    private List<BlockPos> findPath(ClientWorld w, BlockPos start, BlockPos goal) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.f));
        Map<Long, Node> all = new HashMap<>();

        Node s = new Node(start.getX(), start.getY(), start.getZ());
        s.g = 0;
        s.f = heuristic(s.x, s.y, s.z, goal);
        all.put(BlockPos.asLong(s.x, s.y, s.z), s);
        open.add(s);

        Node best = s;
        double bestH = s.f;
        boolean reached = false;
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;
            expansions++;

            double hc = heuristic(cur.x, cur.y, cur.z, goal);
            if (hc < bestH) {
                bestH = hc;
                best = cur;
            }
            if (Math.abs(cur.x - goal.getX()) <= 1 && Math.abs(cur.z - goal.getZ()) <= 1
                    && Math.abs(cur.y - goal.getY()) <= 2) {
                best = cur;
                reached = true;
                break;
            }

            for (int i = 0; i < 8; i++) {
                int nx = cur.x + DX[i];
                int nz = cur.z + DZ[i];
                if (Math.abs(nx - start.getX()) > SEARCH_RADIUS || Math.abs(nz - start.getZ()) > SEARCH_RADIUS) continue;
                boolean diag = i >= 4;
                double base = diag ? 1.414 : 1.0;
                for (int dy : (diag ? DY_FLAT : DY_ALL)) {
                    int ny = cur.y + dy;
                    if (!canStep(w, cur.x, cur.y, cur.z, nx, ny, nz, dy, diag)) continue;
                    double cost = base + (dy > 0 ? 0.6 : 0.0) + (dy < 0 ? 0.3 * -dy : 0.0);
                    double ng = cur.g + cost;
                    long key = BlockPos.asLong(nx, ny, nz);
                    Node nb = all.get(key);
                    if (nb == null) {
                        nb = new Node(nx, ny, nz);
                        all.put(key, nb);
                    }
                    if (ng < nb.g) {
                        nb.g = ng;
                        nb.f = ng + heuristic(nx, ny, nz, goal);
                        nb.parent = cur;
                        nb.closed = false;
                        open.add(nb);
                    }
                }
            }
        }

        if (best == s) return reached ? new ArrayList<>() : null;
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Node n = best; n != null && n != s; n = n.parent) {
            out.add(new BlockPos(n.x, n.y, n.z));
        }
        Collections.reverse(out);
        return out;
    }

    private static double heuristic(int x, int y, int z, BlockPos g) {
        double dx = Math.abs(x - g.getX());
        double dz = Math.abs(z - g.getZ());
        return Math.max(dx, dz) + 0.41 * Math.min(dx, dz) + 0.5 * Math.abs(y - g.getY());
    }

    private boolean canStep(ClientWorld w, int x, int y, int z, int nx, int ny, int nz, int dy, boolean diag) {
        if (!standable(w, nx, ny, nz)) return false;
        if (dy > 0 && !passable(w, x, y + 2, z)) return false;
        if (dy < 0) {
            for (int yy = ny + 2; yy <= y + 1; yy++) {
                if (!passable(w, nx, yy, nz)) return false;
            }
        }
        if (diag) {
            if (!passable(w, nx, y, z) || !passable(w, nx, y + 1, z)
                    || !passable(w, x, y, nz) || !passable(w, x, y + 1, nz)) return false;
        }
        return true;
    }

    private boolean standable(ClientWorld w, int x, int y, int z) {
        return passable(w, x, y, z) && passable(w, x, y + 1, z) && hasGround(w, x, y - 1, z);
    }

    private boolean passable(ClientWorld w, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = w.getBlockState(pos);
        return st.getCollisionShape(w, pos).isEmpty() && st.getFluidState().isEmpty();
    }

    private boolean hasGround(ClientWorld w, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState st = w.getBlockState(pos);
        return !st.getCollisionShape(w, pos).isEmpty();
    }

    // =====================================================================
    //  Movement
    // =====================================================================
    private void moveToward(MinecraftClient mc, ClientPlayerEntity p, Vec3d point, boolean needJump) {
        GameOptions o = mc.options;
        double dx = point.x - p.getX();
        double dz = point.z - p.getZ();
        if (dx * dx + dz * dz < 0.0025) {
            releaseMovement(mc);
            return;
        }
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;

        boolean fwd;
        boolean back = false, left = false, right = false;
        if (Config.autoFace) {
            p.setYaw(approach(p.getYaw(), wantYaw));
            fwd = Math.abs(MathHelper.wrapDegrees(wantYaw - p.getYaw())) < 50.0F;
        } else {
            float rel = MathHelper.wrapDegrees(wantYaw - p.getYaw());
            float a = Math.abs(rel);
            fwd = a < 67.5F;
            back = a > 112.5F;
            left = rel < -22.5F && rel > -157.5F;
            right = rel > 22.5F && rel < 157.5F;
        }

        boolean jump = p.isOnGround() && (needJump || p.horizontalCollision);
        if (p.isTouchingWater()) jump = true;
        if (unstickTicks > 0) {
            unstickTicks--;
            jump = p.isOnGround();
            left = !strafeRight;
            right = strafeRight;
        }

        o.forwardKey.setPressed(fwd);
        o.backKey.setPressed(back);
        o.leftKey.setPressed(left);
        o.rightKey.setPressed(right);
        o.sprintKey.setPressed(fwd && !left && !right);
        o.jumpKey.setPressed(jump);
    }

    private void trackStuck(ClientPlayerEntity p) {
        if (++stuckTimer < 20) return;
        stuckTimer = 0;
        Vec3d now = posOf(p);
        if (checkpoint != null && movedThisWindow && now.squaredDistanceTo(checkpoint) < 0.25) {
            unstickTicks = 12;
            strafeRight = !strafeRight;
            path = null;
            stuckStreak++;
            if (stuckStreak >= 3) {
                if (navEntityId >= 0) blacklist.put(navEntityId, tickCount + 200);
                else skipWaypoint = true;
                stuckStreak = 0;
            }
        } else if (movedThisWindow) {
            stuckStreak = 0;
        }
        checkpoint = now;
        movedThisWindow = false;
    }

    private void face(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float wantPitch = (float) -(MathHelper.atan2(dy, horiz) * 180.0 / Math.PI);
        p.setYaw(approach(p.getYaw(), wantYaw));
        p.setPitch(MathHelper.clamp(approach(p.getPitch(), wantPitch), -90.0F, 90.0F));
    }

    private float approach(float current, float wanted) {
        float diff = MathHelper.wrapDegrees(wanted - current);
        return current + MathHelper.clamp(diff, -TURN_SPEED, TURN_SPEED);
    }

    // =====================================================================
    //  Helpers
    // =====================================================================
    private static Vec3d posOf(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    private static BlockPos blockOf(Entity e) {
        return new BlockPos(MathHelper.floor(e.getX()), MathHelper.floor(e.getY()), MathHelper.floor(e.getZ()));
    }

    private static double hdist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void hud(ClientPlayerEntity p, String text) {
        if (++hudTimer < HUD_INTERVAL) return;
        hudTimer = 0;
        p.sendMessage(Text.literal(text), true);
    }

    private void chat(MinecraftClient mc, String msg) {
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal(msg));
    }

    /** Releases movement keys only; the right-click is handled by updateHoldUse. */
    private void releaseMovement(MinecraftClient mc) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.sprintKey.setPressed(false);
        o.jumpKey.setPressed(false);
    }
}
