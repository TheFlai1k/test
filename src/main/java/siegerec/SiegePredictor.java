package siegerec;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
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

    private static String hudText = null;

    static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(SiegePredictor::renderWorld);
        HudRenderCallback.EVENT.register(SiegePredictor::renderHud);
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

    // Линия и круг рисуются прямо в мире (как обводка блока), поэтому они гладкие
    // и стоят на месте, как бы ты ни двигал камеру (в том числе фрикам).
    private static void renderWorld(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        hudText = null;
        if (!enabled || mc.player == null || mc.world == null) return;
        int w = SiegeRecClient.labelIdx;
        if (w < 0 || w > 2) return;
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;

        // прицел считается от самого игрока: его позиция и его взгляд
        Result res = compute(mc, w, mc.player.getYaw(), mc.player.getPitch());

        if (res.landing != null) {
            Vec3d l = res.landing;
            double dist = Math.hypot(l.x - mc.player.getX(), l.z - mc.player.getZ());
            hudText = String.format(Locale.ROOT, "%s | падение: %d %d %d | дальность %.0f бл.",
                    NAMES[w], (int) Math.floor(l.x), (int) Math.floor(l.y), (int) Math.floor(l.z), dist);
        } else {
            hudText = NAMES[w] + " | падение не видно: " + res.stop;
        }

        Vec3d cam = ctx.camera().getPos();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry e = matrices.peek();

        // белая линия траектории
        List<Vec3d> pts = new ArrayList<>(res.pts);
        if (res.landing != null) pts.add(res.landing);
        for (int i = 0; i + 1 < pts.size(); i++) {
            seg(vc, e, pts.get(i), pts.get(i + 1), cam, 255, 255, 255, 255);
        }

        if (res.landing != null) {
            Vec3d l = res.landing;
            double gy = l.y + 0.1;
            // красный круг на земле
            int segs = 48;
            for (double rad : new double[]{RING_RADIUS, RING_RADIUS - 0.12}) {
                Vec3d prev = null;
                for (int k = 0; k <= segs; k++) {
                    double a = 2 * Math.PI * k / segs;
                    Vec3d cur = new Vec3d(l.x + rad * Math.cos(a), gy, l.z + rad * Math.sin(a));
                    if (prev != null) seg(vc, e, prev, cur, cam, 255, 40, 40, 255);
                    prev = cur;
                }
            }
            // крестик в центре и столб вверх, чтобы место было видно издалека
            seg(vc, e, new Vec3d(l.x - 0.6, gy, l.z), new Vec3d(l.x + 0.6, gy, l.z), cam, 255, 40, 40, 255);
            seg(vc, e, new Vec3d(l.x, gy, l.z - 0.6), new Vec3d(l.x, gy, l.z + 0.6), cam, 255, 40, 40, 255);
            seg(vc, e, new Vec3d(l.x, gy, l.z), new Vec3d(l.x, gy + 8, l.z), cam, 255, 40, 40, 255);
        }
    }

    private static void seg(VertexConsumer vc, MatrixStack.Entry e, Vec3d a, Vec3d b, Vec3d cam,
                            int r, int g, int bl, int al) {
        float ax = (float) (a.x - cam.x), ay = (float) (a.y - cam.y), az = (float) (a.z - cam.z);
        float bx = (float) (b.x - cam.x), by = (float) (b.y - cam.y), bz = (float) (b.z - cam.z);
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        vc.vertex(e, ax, ay, az).color(r, g, bl, al).normal(e, nx, ny, nz);
        vc.vertex(e, bx, by, bz).color(r, g, bl, al).normal(e, nx, ny, nz);
    }

    private static void renderHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (hudText == null || mc.options.hudHidden) return;
        int W = mc.getWindow().getScaledWidth();
        int tw = mc.textRenderer.getWidth(hudText);
        ctx.drawTextWithShadow(mc.textRenderer, Text.literal(hudText), (W - tw) / 2, 18, 0xFFFFFFFF);
    }
}
