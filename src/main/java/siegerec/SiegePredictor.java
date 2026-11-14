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

    private static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null || mc.world == null || mc.options.hudHidden) return;
        int w = SiegeRecClient.labelIdx;
        if (w < 0 || w > 2) return;

        Camera cam = mc.gameRenderer.getCamera();
        boolean first = mc.options.getPerspective().isFirstPerson();
        double yaw = first ? cam.getYaw() : mc.player.getYaw();
        double pitch = first ? cam.getPitch() : mc.player.getPitch();

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

        // линия траектории (каждая 2-я точка = раз в блок)
        for (int i = 0; i < res.pts.size(); i += 2) {
            double[] sp = project(res.pts.get(i), cp, fx, fy, fz, rx, ry, rz, ux, uy, uz, W, H, scale);
            if (sp == null) continue;
            int x = (int) sp[0], y = (int) sp[1];
            ctx.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFE04A);
        }

        String line;
        if (res.landing != null) {
            Vec3d l = res.landing;
            double dist = Math.hypot(l.x - mc.player.getX(), l.z - mc.player.getZ());
            line = String.format(Locale.ROOT, "%s | падение: %d %d %d | дальность %.0f бл.",
                    NAMES[w], (int) Math.floor(l.x), (int) Math.floor(l.y), (int) Math.floor(l.z), dist);
            double[] sp = project(l, cp, fx, fy, fz, rx, ry, rz, ux, uy, uz, W, H, scale);
            if (sp != null) {
                int x = (int) sp[0], y = (int) sp[1];
                ctx.fill(x - 4, y - 4, x + 4, y + 4, 0xFFFF3030);
                ctx.fill(x - 2, y - 2, x + 2, y + 2, 0xFFFFFFFF);
                ctx.drawTextWithShadow(mc.textRenderer, Text.literal("Падение"), x + 7, y - 4, 0xFFFF6060);
            }
        } else {
            line = NAMES[w] + " | падение не видно: " + res.stop;
        }
        int tw = mc.textRenderer.getWidth(line);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(line), (W - tw) / 2, 18, 0xFFFFFFFF);
    }

    private static double[] project(Vec3d p, Vec3d cp,
                                    double fx, double fy, double fz,
                                    double rx, double ry, double rz,
                                    double ux, double uy, double uz,
                                    int W, int H, double scale) {
        double dx = p.x - cp.x, dy = p.y - cp.y, dz = p.z - cp.z;
        double cz = dx * fx + dy * fy + dz * fz;
        if (cz <= 0.05) return null;
        double cx = dx * rx + dy * ry + dz * rz;
        double cyy = dx * ux + dy * uy + dz * uz;
        double sx = W / 2.0 + cx / cz * scale;
        double sy = H / 2.0 - cyy / cz * scale;
        if (sx < -50 || sx > W + 50 || sy < -50 || sy > H + 50) return null;
        return new double[]{sx, sy};
    }
}
