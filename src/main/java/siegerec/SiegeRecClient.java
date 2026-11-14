package siegerec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class SiegeRecClient implements ClientModInitializer {

    private static final String[] LABELS = {"требушет", "катапульта", "баллиста", "без метки"};

    private static class Track {
        int id;
        String type;
        List<double[]> pts = new ArrayList<>();
        int still = 0;
        boolean moved = false;
        long startTick;
        // контекст (запоминается до первого движения снаряда)
        float yaw, pitch;
        double px, py, pz;
        String vehicle = "нет";
        float vehicleYaw = 0;
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Set<Integer> finished = new HashSet<>();
    private boolean recording = true;
    static int labelIdx = 3;
    private long tick = 0;
    private KeyBinding toggleKey;
    private KeyBinding labelKey;
    private KeyBinding predictKey;
    private KeyBinding markKey;

    // последний записанный выстрел (для сравнения с реальным местом падения)
    private int shotNo = 0;
    private double[] lastLaunch = null;
    private double[] lastPredThin = null, lastPredWide = null;
    private String lastLabel = "";
    private double lastPx, lastPz;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.siegerec"));
        labelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.label", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.siegerec"));
        predictKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.predict", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_L, "category.siegerec"));
        markKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.siegerec.mark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, "category.siegerec"));
        SiegePredictor.init();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[SiegeRec] " + s), false);
    }

    private boolean isCandidate(Entity e, MinecraftClient mc) {
        if (e == mc.player || e instanceof PlayerEntity) return false;
        if (e == mc.player.getVehicle()) return false;
        if (e instanceof ItemEntity || e instanceof ExperienceOrbEntity) return false;
        if (e instanceof LivingEntity && !(e instanceof ArmorStandEntity)) return false;
        return true;
    }

    private void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            tracks.clear();
            finished.clear();
            return;
        }
        while (toggleKey.wasPressed()) {
            recording = !recording;
            if (!recording) { tracks.clear(); finished.clear(); }
            msg(mc, recording ? "запись ВКЛЮЧЕНА" : "запись выключена");
        }
        while (markKey.wasPressed()) {
            markLanding(mc);
        }
        while (predictKey.wasPressed()) {
            SiegePredictor.enabled = !SiegePredictor.enabled;
            msg(mc, SiegePredictor.enabled ? "прицел-предсказание ВКЛЮЧЁН" : "прицел-предсказание выключен");
        }
        while (labelKey.wasPressed()) {
            labelIdx = (labelIdx + 1) % LABELS.length;
            msg(mc, "орудие: " + LABELS[labelIdx] + (labelIdx < 3 ? " (прицел показывается)" : " (прицел скрыт)"));
        }
        if (!recording) return;
        tick++;

        Set<Integer> seen = new HashSet<>();
        for (Entity e : mc.world.getEntities()) {
            if (!isCandidate(e, mc)) continue;
            int id = e.getId();
            seen.add(id);
            if (finished.contains(id)) continue;

            Track t = tracks.get(id);
            if (t == null) {
                t = new Track();
                t.id = id;
                t.type = Registries.ENTITY_TYPE.getId(e.getType()).toString();
                t.startTick = tick;
                tracks.put(id, t);
            }
            double[] p = {e.getX(), e.getY(), e.getZ()};
            boolean movedNow = false;
            if (!t.pts.isEmpty()) {
                double[] l = t.pts.get(t.pts.size() - 1);
                double dx = p[0] - l[0], dy = p[1] - l[1], dz = p[2] - l[2];
                movedNow = dx * dx + dy * dy + dz * dz > 1e-6;
            }
            if (movedNow) { t.still = 0; if (!t.moved) t.moved = true; }
            else if (!t.pts.isEmpty()) t.still++;

            if (!t.moved) {
                // снаряд ещё не двигался: обновляем контекст (куда смотрит игрок и т.д.)
                t.yaw = mc.player.getYaw();
                t.pitch = mc.player.getPitch();
                t.px = mc.player.getX();
                t.py = mc.player.getY();
                t.pz = mc.player.getZ();
                Entity v = mc.player.getVehicle();
                t.vehicle = v == null ? "нет" : Registries.ENTITY_TYPE.getId(v.getType()).toString();
                t.vehicleYaw = v == null ? 0 : v.getYaw();
                t.pts.clear();
                t.startTick = tick;
            }
            t.pts.add(p);
        }

        // завершение записей
        Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Track t = it.next().getValue();
            boolean gone = !seen.contains(t.id);
            boolean landed = t.moved && t.still >= 10;
            if (gone || landed) {
                if (landed) {
                    for (int i = 0; i < t.still && t.pts.size() > 1; i++) t.pts.remove(t.pts.size() - 1);
                    finished.add(t.id);
                }
                if (t.moved) finish(mc, t);
                it.remove();
            }
        }
        finished.retainAll(seen);
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        if (v == null) return a;
        for (double d : v) a.add(r(d));
        return a;
    }

    private static double hd(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    /** Клавиша U: навёл камеру (хоть фрикам) на место, куда упал снаряд, и нажал. */
    private void markLanding(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (lastLaunch == null) { msg(mc, "сначала сделай выстрел"); return; }
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d start = cam.getPos();
        double yw = Math.toRadians(cam.getYaw()), pt = Math.toRadians(cam.getPitch());
        Vec3d dir = new Vec3d(-Math.sin(yw) * Math.cos(pt), -Math.sin(pt), Math.cos(yw) * Math.cos(pt));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(start, start.add(dir.multiply(400)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) { msg(mc, "не вижу блок под прицелом, наведи на место падения"); return; }
        Vec3d h = hit.getPos();
        double[] actual = {h.x, h.y, h.z};

        JsonObject o = new JsonObject();
        o.addProperty("type", "landing_mark");
        o.addProperty("shot", shotNo);
        o.addProperty("label", lastLabel);
        o.add("launch", arr(lastLaunch));
        o.add("actual", arr(actual));
        o.add("pred_thin", arr(lastPredThin));
        o.add("pred_wide", arr(lastPredWide));
        double ad = hd(actual, new double[]{lastPx, 0, lastPz});
        o.addProperty("actual_distance_from_player", r(ad));
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "падение выстрела #%d [%s]: %.0f бл. от тебя", shotNo, lastLabel, ad));
        if (lastPredWide != null) {
            double ew = hd(actual, lastPredWide);
            o.addProperty("error_wide", r(ew));
            sb.append(String.format(Locale.ROOT, ", прицел (с запасом) ошибся на %.1f", ew));
        }
        if (lastPredThin != null) {
            double et = hd(actual, lastPredThin);
            o.addProperty("error_thin", r(et));
            sb.append(String.format(Locale.ROOT, ", без запаса на %.1f", et));
        }
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("siege_logs").resolve("siege_log.jsonl");
            Files.createDirectories(f.getParent());
            Files.writeString(f, o + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            msg(mc, "не смог записать файл: " + ex.getMessage());
            return;
        }
        msg(mc, sb.toString());
    }

    private static double r(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private void finish(MinecraftClient mc, Track t) {
        if (t.pts.size() < 3) return;
        double[] a = t.pts.get(0);
        double[] b = t.pts.get(t.pts.size() - 1);
        double horiz = Math.hypot(b[0] - a[0], b[2] - a[2]);
        double path = 0;
        for (int i = 1; i < t.pts.size(); i++) {
            double[] p = t.pts.get(i - 1), q = t.pts.get(i);
            path += Math.sqrt(Math.pow(q[0]-p[0],2)+Math.pow(q[1]-p[1],2)+Math.pow(q[2]-p[2],2));
        }
        if (path < 3 || horiz < 8) return; // мелочь и падающие вниз штуки, не выстрел

        JsonObject o = new JsonObject();
        o.addProperty("label", LABELS[labelIdx]);
        o.addProperty("entity", t.type);
        o.addProperty("ticks", t.pts.size());
        o.addProperty("horizontal_distance", r(horiz));
        o.addProperty("player_yaw", r(t.yaw));
        o.addProperty("player_pitch", r(t.pitch));
        o.addProperty("vehicle", t.vehicle);
        o.addProperty("vehicle_yaw", r(t.vehicleYaw));
        JsonArray pp = new JsonArray();
        pp.add(r(t.px)); pp.add(r(t.py)); pp.add(r(t.pz));
        o.add("player_pos", pp);
        JsonArray pts = new JsonArray();
        for (double[] p : t.pts) {
            JsonArray x = new JsonArray();
            x.add(r(p[0])); x.add(r(p[1])); x.add(r(p[2]));
            pts.add(x);
        }
        o.add("points", pts);

        // что предсказывал прицел для этого выстрела (по записанному взгляду и позиции игрока)
        double[] predThin = null, predWide = null;
        if (labelIdx < 3) {
            SiegePredictor.Result rt = SiegePredictor.compute(mc, labelIdx, t.yaw, t.pitch, t.px, t.py, t.pz, 0);
            SiegePredictor.Result rw = SiegePredictor.compute(mc, labelIdx, t.yaw, t.pitch, t.px, t.py, t.pz, SiegePredictor.WIDE_MARGIN);
            if (rt.landing != null) predThin = new double[]{rt.landing.x, rt.landing.y, rt.landing.z};
            if (rw.landing != null) predWide = new double[]{rw.landing.x, rw.landing.y, rw.landing.z};
            o.add("pred_thin", arr(predThin));
            o.add("pred_wide", arr(predWide));
        }
        shotNo++;
        o.addProperty("shot", shotNo);
        lastLaunch = new double[]{a[0], a[1], a[2]};
        lastPredThin = predThin;
        lastPredWide = predWide;
        lastLabel = LABELS[labelIdx];
        lastPx = t.px;
        lastPz = t.pz;

        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("siege_logs").resolve("siege_log.jsonl");
            Files.createDirectories(f.getParent());
            Files.writeString(f, o + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            msg(mc, "не смог записать файл: " + ex.getMessage());
            return;
        }
        msg(mc, String.format(Locale.ROOT,
                "записан выстрел [%s]: %s, %d тиков, по горизонтали %.1f бл., падение %.1f %.1f %.1f, угол %.1f",
                LABELS[labelIdx], t.type, t.pts.size(), horiz, b[0], b[1], b[2], t.pitch));
    }
}
