package siegerec;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Доводка прицела: сама подбирает наклон и направление, чтобы снаряд упал в цель,
 * и плавно доворачивает взгляд. Стреляет игрок сам.
 *
 * Y — цель: игрок или точка под прицелом камеры (можно из фрикама).
 * N — цель: лучшая кучка врагов среди видимых игроков.
 * H — пауза / продолжить доводку.
 * Свои (не бить): файл siege_logs/friends.txt, по нику на строку.
 */
public class SiegeAim {

    private enum Mode { NONE, POINT, PLAYER, CLUSTER }

    static final double RADIUS = 3.0;       // радиус поражения снаряда, блоков
    private static final double HIT_TOL = 3.5; // допуск попадания при проверке пути

    static boolean aimOn = false;
    static Vec3d markerPos = null;
    static String line1 = null, line2 = null;
    static boolean warn = false;

    private static Mode mode = Mode.NONE;
    private static Vec3d pointTarget = null;
    private static int playerId = -1;
    private static String playerName = "";
    private static Vec3d clusterCenter = null;
    private static int clusterCount = 0;

    private static boolean haveDesired = false;
    private static double desiredYaw, desiredPitch;
    private static double lastSetYaw, lastSetPitch;
    private static boolean ownedRot = false;
    private static long lastFrameNs = 0;
    private static long tickN = 0;

    // позиции и скорости игроков: id -> {x, y, z, vx, vy, vz}
    private static final Map<Integer, double[]> trk = new HashMap<>();
    private static final Set<String> friends = new HashSet<>();

    private static KeyBinding targetKey, clusterKey, toggleKey;

    private static class Sol {
        double up, yaw; // yaw в радианах (как в Minecraft)
        boolean clear;
        double flight, dist;
        Vec3d landing;
    }

    static void init() {
        targetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.aim_target", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, "category.siegerec"));
        clusterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.aim_cluster", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.siegerec"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.aim_toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.siegerec"));
        ClientTickEvents.END_CLIENT_TICK.register(SiegeAim::tick);
    }

    private static void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[SiegeAim] " + s), false);
    }

    // ---------- свои ----------

    private static void loadFriends() {
        friends.clear();
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("siege_logs").resolve("friends.txt");
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, "# Ники своих игроков, по одному на строку. Доводка не будет целиться рядом с ними.\n",
                        StandardCharsets.UTF_8);
                return;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                friends.add(t.toLowerCase(Locale.ROOT));
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isFriend(AbstractClientPlayerEntity p) {
        return friends.contains(p.getName().getString().toLowerCase(Locale.ROOT));
    }

    // ---------- отслеживание игроков ----------

    private static void updateTracks(MinecraftClient mc) {
        Set<Integer> seen = new HashSet<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            int id = p.getId();
            seen.add(id);
            double[] t = trk.get(id);
            if (t == null) {
                trk.put(id, new double[]{p.getX(), p.getY(), p.getZ(), 0, 0, 0});
            } else {
                double dx = p.getX() - t[0], dy = p.getY() - t[1], dz = p.getZ() - t[2];
                t[3] = 0.6 * t[3] + 0.4 * dx;
                t[4] = 0.6 * t[4] + 0.4 * dy;
                t[5] = 0.6 * t[5] + 0.4 * dz;
                t[0] = p.getX(); t[1] = p.getY(); t[2] = p.getZ();
            }
        }
        trk.keySet().retainAll(seen);
    }

    private static double weightOf(AbstractClientPlayerEntity p) {
        double[] t = trk.get(p.getId());
        if (t == null) return 1.0;
        return Math.hypot(t[3], t[5]) < 0.03 ? 1.0 : 0.5; // стоящих считаем выгоднее бегущих
    }

    // ---------- выбор цели ----------

    private static void pickTarget(MinecraftClient mc) {
        loadFriends();
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d start = cam.getPos();
        double yw = Math.toRadians(cam.getYaw()), pt = Math.toRadians(cam.getPitch());
        Vec3d dir = new Vec3d(-Math.sin(yw) * Math.cos(pt), -Math.sin(pt), Math.cos(yw) * Math.cos(pt));
        Vec3d end = start.add(dir.multiply(400));
        BlockHitResult bh = mc.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        double blockDist = bh.getType() == HitResult.Type.BLOCK ? start.distanceTo(bh.getPos()) : 1e9;

        AbstractClientPlayerEntity bestP = null;
        double bestD = 1e9;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Optional<Vec3d> hit = p.getBoundingBox().expand(0.4).raycast(start, end);
            if (hit.isPresent()) {
                double d = start.distanceTo(hit.get());
                if (d < bestD) { bestD = d; bestP = p; }
            }
        }
        if (bestP != null && bestD <= blockDist + 1.0) {
            if (isFriend(bestP)) { msg(mc, bestP.getName().getString() + " в списке своих, цель не выбрана"); return; }
            mode = Mode.PLAYER;
            playerId = bestP.getId();
            playerName = bestP.getName().getString();
            aimOn = true;
            ownedRot = false;
            msg(mc, "цель: игрок " + playerName + ", доводка включена");
        } else if (bh.getType() == HitResult.Type.BLOCK) {
            mode = Mode.POINT;
            pointTarget = bh.getPos();
            aimOn = true;
            ownedRot = false;
            msg(mc, String.format(Locale.ROOT, "цель: точка %d %d %d, доводка включена",
                    (int) Math.floor(pointTarget.x), (int) Math.floor(pointTarget.y), (int) Math.floor(pointTarget.z)));
        } else {
            mode = Mode.NONE;
            aimOn = false;
            msg(mc, "цель сброшена");
        }
    }

    // ---------- поиск лучшей кучки ----------

    private static void updateCluster(MinecraftClient mc, int w) {
        List<AbstractClientPlayerEntity> enemies = new ArrayList<>();
        List<AbstractClientPlayerEntity> own = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (isFriend(p)) own.add(p); else enemies.add(p);
        }
        if (enemies.isEmpty()) { clusterCenter = null; clusterCount = 0; return; }

        List<double[]> cands = new ArrayList<>(); // {score, x, y, z}
        for (AbstractClientPlayerEntity seed : enemies) {
            double sx = 0, sy = 0, sz = 0; int n = 0;
            for (AbstractClientPlayerEntity e : enemies) {
                if (Math.hypot(e.getX() - seed.getX(), e.getZ() - seed.getZ()) <= RADIUS
                        && Math.abs(e.getY() - seed.getY()) <= 3) {
                    sx += e.getX(); sy += e.getY(); sz += e.getZ(); n++;
                }
            }
            double gx = sx / n, gy = sy / n, gz = sz / n;
            double score = 0;
            for (AbstractClientPlayerEntity e : enemies) {
                if (Math.hypot(e.getX() - gx, e.getZ() - gz) <= RADIUS && Math.abs(e.getY() - gy) <= 3) score += weightOf(e);
            }
            boolean friendNear = false;
            for (AbstractClientPlayerEntity f : own) {
                if (Math.hypot(f.getX() - gx, f.getZ() - gz) <= RADIUS + 1.0 && Math.abs(f.getY() - gy) <= 3) friendNear = true;
            }
            if (!friendNear) cands.add(new double[]{score, gx, gy, gz});
        }
        cands.sort((a, b) -> Double.compare(b[0], a[0]));

        // текущая кучка: оставляем, пока новая не заметно лучше (чтобы прицел не прыгал)
        double curScore = 0;
        if (clusterCenter != null) {
            for (AbstractClientPlayerEntity e : enemies) {
                if (Math.hypot(e.getX() - clusterCenter.x, e.getZ() - clusterCenter.z) <= RADIUS
                        && Math.abs(e.getY() - clusterCenter.y) <= 3) curScore += weightOf(e);
            }
        }
        int tries = 0;
        for (double[] c : cands) {
            if (tries++ >= 6) break;
            if (clusterCenter != null && curScore > 0 && c[0] <= curScore + 0.4) break;
            Vec3d g = new Vec3d(c[1], c[2], c[3]);
            Sol s = best(mc, w, g);
            if (s != null && s.clear) {
                clusterCenter = g;
                int cnt = 0;
                for (AbstractClientPlayerEntity e : enemies) {
                    if (Math.hypot(e.getX() - g.x, e.getZ() - g.z) <= RADIUS && Math.abs(e.getY() - g.y) <= 3) cnt++;
                }
                clusterCount = cnt;
                return;
            }
        }
        if (curScore <= 0) { clusterCenter = null; clusterCount = 0; }
    }

    // ---------- решение: какой наклон и направление ----------

    /** Лучшее решение для точки T. Пологие и навесные пути проверяются на препятствия; навесной предпочтителен. */
    private static Sol best(MinecraftClient mc, int w, Vec3d T) {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double fwd = SiegePredictor.FWD[w];
        double yaw = Math.atan2(-(T.x - px), T.z - pz);
        for (int i = 0; i < 4; i++) {
            double ox = px - Math.sin(yaw) * fwd, oz = pz + Math.cos(yaw) * fwd;
            yaw = Math.atan2(-(T.x - ox), T.z - oz);
        }
        double ox = px - Math.sin(yaw) * fwd, oz = pz + Math.cos(yaw) * fwd;
        double S = Math.hypot(T.x - ox, T.z - oz);
        if (S < 8) return null;

        List<Double> roots = SiegePredictor.solveUp(w, py, S, T.y);
        if (roots.isEmpty()) return null;
        roots.sort((a, b) -> Double.compare(b, a)); // сначала навесной (круче)

        Sol fallback = null;
        for (double up : roots) {
            Sol s = new Sol();
            s.up = up;
            s.yaw = yaw;
            s.dist = S;
            s.flight = SiegePredictor.flightTicks(w, up, S);
            SiegePredictor.Result r = SiegePredictor.compute(mc, w, Math.toDegrees(yaw), -up, px, py, pz,
                    SiegePredictor.WIDE_MARGIN);
            s.landing = r.landing;
            s.clear = r.landing != null
                    && Math.hypot(r.landing.x - T.x, r.landing.z - T.z) <= HIT_TOL
                    && Math.abs(r.landing.y - T.y) <= 3.0;
            if (s.clear) return s;
            if (fallback == null) fallback = s;
        }
        return fallback;
    }

    // ---------- каждый тик ----------

    static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            mode = Mode.NONE; aimOn = false; haveDesired = false; trk.clear();
            line1 = line2 = null; markerPos = null;
            return;
        }
        tickN++;
        updateTracks(mc);

        while (targetKey.wasPressed()) pickTarget(mc);
        while (clusterKey.wasPressed()) {
            loadFriends();
            mode = Mode.CLUSTER;
            clusterCenter = null;
            aimOn = true;
            ownedRot = false;
            msg(mc, "цель: лучшая кучка, доводка включена");
            updateCluster(mc, SiegeRecClient.labelIdx);
        }
        while (toggleKey.wasPressed()) {
            if (mode == Mode.NONE) { msg(mc, "сначала выбери цель: Y или N"); continue; }
            aimOn = !aimOn;
            ownedRot = false;
            msg(mc, aimOn ? "доводка продолжена" : "доводка на паузе");
        }

        line1 = null; line2 = null; warn = false; markerPos = null; haveDesired = false;
        if (mode == Mode.NONE) return;

        int w = SiegeRecClient.labelIdx;
        if (w < 0 || w > 2) { line1 = "Доводка: выбери орудие клавишей K"; return; }

        String desc;
        Vec3d base;
        if (mode == Mode.POINT) {
            base = pointTarget;
            desc = "точка";
        } else if (mode == Mode.PLAYER) {
            Entity e = mc.world.getEntityById(playerId);
            if (e == null) { line1 = "Доводка: цель " + playerName + " потеряна из виду"; return; }
            base = new Vec3d(e.getX(), e.getY(), e.getZ());
            desc = "игрок " + playerName;
        } else {
            if (tickN % 10 == 0 || clusterCenter == null) updateCluster(mc, w);
            if (clusterCenter == null) { line1 = "Доводка: подходящей кучки не видно"; return; }
            base = clusterCenter;
            desc = "кучка (" + clusterCount + " чел.)";
        }

        // упреждение для идущего игрока
        Vec3d aimPt = base;
        Sol sol = null;
        double lead = 0;
        for (int it = 0; it < 3; it++) {
            sol = best(mc, w, aimPt);
            if (sol == null || mode != Mode.PLAYER) break;
            double[] v = trk.get(playerId);
            if (v == null || Math.hypot(v[3], v[5]) <= 0.03) break;
            double lx = v[3] * sol.flight, lz = v[5] * sol.flight;
            double len = Math.hypot(lx, lz);
            if (len > 25) { lx *= 25 / len; lz *= 25 / len; len = 25; }
            lead = len;
            aimPt = new Vec3d(base.x + lx, base.y, base.z + lz);
        }
        markerPos = aimPt;

        if (sol == null) {
            line1 = "Доводка: " + desc + " | выстрела не существует (слишком близко или далеко для орудия)";
            warn = true;
            return;
        }

        // свои рядом с точкой попадания
        String friendNear = null;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !isFriend(p)) continue;
            if (Math.hypot(p.getX() - aimPt.x, p.getZ() - aimPt.z) <= RADIUS + 1.0 && Math.abs(p.getY() - aimPt.y) <= 3) {
                friendNear = p.getName().getString();
            }
        }

        String arc = sol.up > 55 ? "навесная" : "пологая";
        line1 = String.format(Locale.ROOT, "Доводка %s | %s | наклон %.1f° | %s | дальность %.0f бл.%s",
                aimOn ? "ВКЛ" : "ПАУЗА", desc, sol.up, arc, sol.dist,
                lead > 1 ? String.format(Locale.ROOT, " | упреждение %.0f бл.", lead) : "");
        if (friendNear != null) {
            line2 = "СВОЙ рядом с целью: " + friendNear + " — доводка остановлена";
            warn = true;
            return;
        }
        if (!sol.clear) {
            line2 = "ПРЕПЯТСТВИЕ: нет чистого пути до цели (стена или зубцы)";
            warn = true;
            return;
        }
        desiredYaw = MathHelper.wrapDegrees(Math.toDegrees(sol.yaw));
        desiredPitch = -sol.up;
        haveDesired = aimOn;
    }

    // ---------- каждый кадр: плавный доворот ----------

    static void frame(MinecraftClient mc) {
        if (mc.player == null || !aimOn || !haveDesired) { lastFrameNs = 0; return; }
        long now = System.nanoTime();
        double dt = lastFrameNs == 0 ? 0.016 : (now - lastFrameNs) / 1e9;
        lastFrameNs = now;
        dt = Math.min(dt, 0.1);

        double cy = mc.player.getYaw(), cp = mc.player.getPitch();
        // если игрок сам повернул мышью — отдаём управление
        if (ownedRot && (Math.abs(MathHelper.wrapDegrees(cy - lastSetYaw)) > 1.0 || Math.abs(cp - lastSetPitch) > 1.0)) {
            aimOn = false;
            haveDesired = false;
            ownedRot = false;
            msg(mc, "доводка выключена (ты повернул сам), H — продолжить");
            return;
        }
        double dyaw = MathHelper.wrapDegrees(desiredYaw - cy);
        double dp = desiredPitch - cp;
        double k = 1 - Math.exp(-dt / 0.12);
        double maxStep = 120.0 * dt;
        double sy = Math.abs(dyaw) < 0.02 ? dyaw : MathHelper.clamp(dyaw * k, -maxStep, maxStep);
        double sp = Math.abs(dp) < 0.02 ? dp : MathHelper.clamp(dp * k, -maxStep, maxStep);
        double ny = cy + sy, np = MathHelper.clamp(cp + sp, -90.0, 90.0);
        mc.player.setYaw((float) ny);
        mc.player.setPitch((float) np);
        lastSetYaw = ny;
        lastSetPitch = np;
        ownedRot = true;
    }
}
