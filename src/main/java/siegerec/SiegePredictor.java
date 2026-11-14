package siegerec;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Предсказание траектории. Порядок орудий: 0 требушет, 1 катапульта, 2 баллиста.
 * Модель по логам: y(s) = y0 + s*tan(t) - C * s^2 / cos^2(t), где s = путь по горизонтали, t = угол вверх.
 */
public class SiegePredictor {

    private static final String[] NAMES = {"Требушет", "Катапульта", "Баллиста"};
    // C = g / (2 v^2), подобрано по логам
    private static final double[] C = {0.00315, 0.00832, 0.00187};
    // откуда вылетает снаряд относительно игрока: вперёд (блоков) и вверх = H0 + H1 * угол_вверх
    private static final double[] FWD = {5.93, 5.42, 5.61};
    private static final double[] H0 = {8.74, 3.82, 3.67};
    private static final double[] H1 = {0.0148, 0.0074, 0.0164};

    public static boolean enabled = true;

    private static class Result {
        final List<Vec3d> pts = new ArrayList<>();
        Vec3d landing = null;
        String stop = "";
    }

    static void init() {
        HudRenderCallback.EVENT.register(SiegePredictor::render);
    }

    private static Result compute(MinecraftClient mc, int w, double yawDeg, double pitchDeg) {
        Result res = new Result();
        ClientWorld world = mc.world;
        double yaw = Math.toRadians(yawDeg);
        double pitch = MathHelper.clamp(pitchDeg, -89.0, 89.0);
        double up = -pitch; // градусы вверх
        double th = Math.toRadians(up);
        double tan = Math.tan(th);
        double cos2 = Math.cos(th) * Math.cos(th);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);

        double x0 = mc.player.getX() + fx * FWD[w];
        double z0 = mc.player.getZ() + fz * FWD[w];
        double y0 = mc.player.getY() + H0[w] + H1[w] * up;

        for (double s = 0; s <= 700; s += 0.5) {
            double x = x0 + fx * s;
            double z = z0 + fz * s;
            double y = y0 + s * tan - C[w] * s * s / cos2;
            Vec3d p = new Vec3d(x, y, z);
            if (!world.getChunkManager().isChunkLoaded(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4)) {
                res.stop = "дальше загруженных чанков";
                return res;
            }
            if (y < world.getBottomY()) {
                res.stop = "ниже мира";
                return res;
            }
            if (s >= 3) {
                BlockPos bp = BlockPos.ofFloored(x, y, z);
                if (!world.getBlockState(bp).getCollisionShape(world, bp).isEmpty()) {
                    res.landing = p;
                    return res;
                }
            }
            res.pts.add(p);
        }
        res.stop = "слишком далеко";
        return res;
    }

    // Радиус красного круга вокруг точки падения (в блоках)
    private static final double RING_RADIUS = 2.5;

    private static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null || mc.world == null || mc.options.hudHidden) return;
        int w = SiegeRecClient.labelIdx;
        if (w < 0 || w > 2) return;

        Camera cam = mc.gameRenderer.getCamera();
        // Прицел считается от самого игрока (его позиция и его взгляд), а не от камеры.
        // Камера (в том числе фрикам) нужна только для того, чтобы нарисовать линию на экране.
        double yaw = mc.player.getYaw();
        double pitch = mc.player.getPitch();

        Result res = compute(mc, w, yaw, pitch);

        int W = mc.getWindow().getScaledWidth();
        int H = mc.getWindow().getScaledHeight();
        double fovRad = Math.toRadians(mc.options.getFov().getValue());
        double scale = (H / 2.0) / Math.tan(fovRad / 2.0);

        Vec3d cp = cam.getPos();
        double cy = Math.toRadians(cam.getYaw());
        double cpit = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(cy) * Math.cos(cpit), fy = -Math.sin(cpit), fz = Math.cos(cy) * Math.cos(cpit);
        double rx = -Math.cos(cy), ry = 0, rz = -Math.sin(cy);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double[] basis = {fx, fy, fz, rx, ry, rz, ux, uy, uz};

        // белая линия траектории
        List<Vec3d> pts = new ArrayList<>(res.pts);
        if (res.landing != null) pts.add(res.landing);
        double[] prev = null;
        for (int i = 0; i < pts.size(); i += 2) {
            double[] cur = project(pts.get(i), cp, basis, W, H, scale);
            line(ctx, prev, cur, 0xFFFFFFFF, W, H);
            prev = cur;
        }
        if (!pts.isEmpty()) {
            double[] last = project(pts.get(pts.size() - 1), cp, basis, W, H, scale);
            line(ctx, prev, last, 0xFFFFFFFF, W, H);
        }

        String text;
        if (res.landing != null) {
            Vec3d l = res.landing;
            double dist = Math.hypot(l.x - mc.player.getX(), l.z - mc.player.getZ());
            text = String.format(Locale.ROOT, "%s | падение: %d %d %d | дальность %.0f бл.",
                    NAMES[w], (int) Math.floor(l.x), (int) Math.floor(l.y), (int) Math.floor(l.z), dist);

            // красный круг на земле вокруг точки падения
            double[] firstRing = null, prevRing = null;
            int segs = 40;
            for (int k = 0; k <= segs; k++) {
                double a = 2 * Math.PI * k / segs;
                Vec3d rp = new Vec3d(l.x + RING_RADIUS * Math.cos(a), l.y + 0.15, l.z + RING_RADIUS * Math.sin(a));
                double[] cur = project(rp, cp, basis, W, H, scale);
                line(ctx, prevRing, cur, 0xFFFF2020, W, H);
                prevRing = cur;
            }
            double[] c = project(new Vec3d(l.x, l.y + 0.15, l.z), cp, basis, W, H, scale);
            if (c != null) {
                int x = (int) c[0], y = (int) c[1];
                ctx.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFF2020);
            }
        } else {
            text = NAMES[w] + " | падение не видно: " + res.stop;
        }
        int tw = mc.textRenderer.getWidth(text);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(text), (W - tw) / 2, 18, 0xFFFFFFFF);
    }

    private static void line(DrawContext ctx, double[] a, double[] b, int color, int W, int H) {
        if (a == null || b == null) return;
        double dx = b[0] - a[0], dy = b[1] - a[1];
        int n = (int) Math.min(Math.max(Math.abs(dx), Math.abs(dy)), 1500);
        if (n < 1) n = 1;
        for (int i = 0; i <= n; i++) {
            int x = (int) (a[0] + dx * i / n);
            int y = (int) (a[1] + dy * i / n);
            if (x < -2 || x > W + 2 || y < -2 || y > H + 2) continue;
            ctx.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static double[] project(Vec3d p, Vec3d cp, double[] b, int W, int H, double scale) {
        double dx = p.x - cp.x, dy = p.y - cp.y, dz = p.z - cp.z;
        double cz = dx * b[0] + dy * b[1] + dz * b[2];
        if (cz <= 0.05) return null;
        double cx = dx * b[3] + dy * b[4] + dz * b[5];
        double cyy = dx * b[6] + dy * b[7] + dz * b[8];
        return new double[]{W / 2.0 + cx / cz * scale, H / 2.0 - cyy / cz * scale};
    }
}
