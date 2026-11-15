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

    static final String[] NAMES = {"Требушет", "Катапульта", "Баллиста"};
    // C = C0 + C1 * sin(угол вылета); подобрано по логам (C = g / (2 v^2) в форме y = s*tan - C*s^2/cos^2)
    private static final double[] C0 = {0.003078, 0.007854, 0.001825};
    private static final double[] C1 = {0.000138, 0.000545, 0.000091};
    // откуда вылетает снаряд относительно игрока: вперёд (блоков) и вверх = H0 + H1 * угол_вверх
    static final double[] FWD = {5.93, 5.42, 5.61};
    private static final double[] H0 = {8.74, 3.82, 3.67};
    private static final double[] H1 = {0.0148, 0.0074, 0.0164};

    // Запас на размер снаряда: проверяем не одну точку, а кубик вокруг неё (в блоках от центра)
    static final double WIDE_MARGIN = 0.4;
    // Радиус красного круга вокруг точки падения (в блоках)
    private static final double RING_RADIUS = 2.5;

    public static boolean enabled = true;
    private static String hudText = null;
    private static String liveText = null;

    static class Result {
        final List<Vec3d> pts = new ArrayList<>();
        Vec3d landing = null;
        String stop = "";
    }

    static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(SiegePredictor::renderWorld);
        HudRenderCallback.EVENT.register(SiegePredictor::renderHud);
    }

    private static boolean solid(ClientWorld world, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        return !world.getBlockState(bp).getCollisionShape(world, bp).isEmpty();
    }

    private static boolean blocked(ClientWorld world, double x, double y, double z, double m) {
        if (m <= 0) return solid(world, x, y, z);
        for (double dx = -m; dx <= m + 1e-9; dx += m)
            for (double dy = -m; dy <= m + 1e-9; dy += m)
                for (double dz = -m; dz <= m + 1e-9; dz += m)
                    if (solid(world, x + dx, y + dy, z + dz)) return true;
        return false;
    }

    /** Линейная интерполяция по таблице {угол, значение}. */
    private static double interp(double[][] t, double x) {
        if (x <= t[0][0]) return t[0][1];
        for (int i = 1; i < t.length; i++) {
            if (x <= t[i][0]) {
                double f = (x - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
                return t[i - 1][1] + f * (t[i][1] - t[i - 1][1]);
            }
        }
        return t[t.length - 1][1];
    }

    // Поправка угла вылета (градусы): насколько плагин стреляет выше (+) или ниже (-), чем смотрит игрок.
    // Таблицы {наклон вверх, поправка}, подобраны по логам.
    private static final double[][] DELTA_TREB = {{-5, 3.7}, {0, 3.7}, {8, -0.2}, {20, 0.9}, {45, 0.0}, {62, 0.6}, {72, 4.3}, {90, 4.3}};
    private static final double[][] DELTA_CAT = {{-5, -2.0}, {10, -2.0}, {50, -1.0}, {62, 0.0}, {72, 4.5}, {90, 4.5}};
    // Баллиста: почти линейно, launch = 1.093 * up - 2.9
    private static final double[][] DELTA_BAL = {{-20, -4.7}, {-5, -3.4}, {0, -2.9}, {20, -1.0}, {45, 1.3}, {70, 3.6}, {90, 5.5}};

    static double launchDelta(int w, double up) {
        if (w == 0) return interp(DELTA_TREB, up);
        if (w == 1) return interp(DELTA_CAT, up);
        return interp(DELTA_BAL, up);
    }

    // Случайный разброс угла вылета у самого плагина (градусы): {на малых углах, на крутых}
    private static final double[][] SPREAD = {{2.6, 0.6}, {4.0, 0.8}, {1.6, 0.3}};

    static double spreadDeg(int w, double upDeg) {
        double f = MathHelper.clamp((upDeg - 45.0) / 23.0, 0.0, 1.0);
        return SPREAD[w][0] + f * (SPREAD[w][1] - SPREAD[w][0]);
    }

    static void drawMarker(VertexConsumer vc, MatrixStack.Entry e, Vec3d cam, Vec3d l,
                                   double rad, double height, int r, int g, int b) {
        double gy = l.y + 0.1;
        int segs = 24;
        Vec3d prev = null;
        for (int k = 0; k <= segs; k++) {
            double a = 2 * Math.PI * k / segs;
            Vec3d cur = new Vec3d(l.x + rad * Math.cos(a), gy, l.z + rad * Math.sin(a));
            if (prev != null) seg(vc, e, prev, cur, cam, r, g, b, 255);
            prev = cur;
        }
        seg(vc, e, new Vec3d(l.x, gy, l.z), new Vec3d(l.x, gy + height, l.z), cam, r, g, b, 255);
    }

    /** Высота снаряда на горизонтальной дистанции S от точки вылета при наклоне up (градусы вверх). */
    static double yAtDist(int w, double up, double S, double py) {
        double upL = MathHelper.clamp(up + launchDelta(w, up), -89.0, 89.0);
        double th = Math.toRadians(upL);
        double cos2 = Math.cos(th) * Math.cos(th);
        double C = C0[w] + C1[w] * Math.sin(th);
        return py + H0[w] + H1[w] * up + S * Math.tan(th) - C * S * S / cos2;
    }

    /** Все наклоны up, при которых снаряд на дистанции S оказывается на высоте ty. Обычно 0–2 штуки. */
    static List<Double> solveUp(int w, double py, double S, double ty) {
        List<Double> roots = new ArrayList<>();
        double prevF = Double.NaN, prevU = 0;
        for (double up = -15; up <= 89.0001; up += 0.1) {
            double f = yAtDist(w, up, S, py) - ty;
            if (!Double.isNaN(prevF) && prevF * f <= 0 && !(prevF == 0 && f == 0)) {
                double lo = prevU, hi = up, flo = prevF;
                for (int i = 0; i < 30; i++) {
                    double mid = (lo + hi) / 2;
                    double fm = yAtDist(w, mid, S, py) - ty;
                    if (flo * fm <= 0) { hi = mid; } else { lo = mid; flo = fm; }
                }
                roots.add((lo + hi) / 2);
            }
            prevF = f;
            prevU = up;
        }
        return roots;
    }

    /** Время полёта в тиках (g = 0.0236 блока/тик^2 по логам). */
    static double flightTicks(int w, double up, double S) {
        double upL = MathHelper.clamp(up + launchDelta(w, up), -89.0, 89.0);
        double th = Math.toRadians(upL);
        double C = C0[w] + C1[w] * Math.sin(th);
        double v = Math.sqrt(0.0236 / (2 * C));
        return S / (v * Math.cos(th));
    }

    /** Полёт по параболе y = y0 + a*s + b*s^2 вдоль горизонтального направления (fx, fz). */
    static Result trace(MinecraftClient mc, double x0, double y0, double z0, double fx, double fz,
                        double a, double b, double margin, double startCheck) {
        Result res = new Result();
        ClientWorld world = mc.world;
        for (double s = 0; s <= 700; s += 0.5) {
            double x = x0 + fx * s;
            double z = z0 + fz * s;
            double y = y0 + a * s + b * s * s;
            Vec3d p = new Vec3d(x, y, z);
            if (!world.getChunkManager().isChunkLoaded(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4)) {
                res.stop = "дальше загруженных чанков";
                return res;
            }
            if (y < world.getBottomY()) {
                res.stop = "ниже мира";
                return res;
            }
            if (s >= startCheck && blocked(world, x, y, z, margin)) {
                res.landing = p;
                return res;
            }
            res.pts.add(p);
        }
        res.stop = "слишком далеко";
        return res;
    }

    /** margin = 0: снаряд как точка; margin > 0: снаряд как кубик с запасом. */
    static Result compute(MinecraftClient mc, int w, double yawDeg, double pitchDeg,
                          double px, double py, double pz, double margin) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = MathHelper.clamp(pitchDeg, -89.0, 89.0);
        double up = -pitch; // градусы вверх
        double upL = MathHelper.clamp(up + launchDelta(w, up), -89.0, 89.0);
        double th = Math.toRadians(upL);
        double cos2 = Math.cos(th) * Math.cos(th);
        double C = C0[w] + C1[w] * Math.sin(th);
        double a = Math.tan(th), b = -C / cos2;
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);

        double x0 = px + fx * FWD[w];
        double z0 = pz + fz * FWD[w];
        double y0 = py + H0[w] + H1[w] * up;
        return trace(mc, x0, y0, z0, fx, fz, a, b, margin, margin > 0 ? 8 : 3);
    }

    /** Подгонка параболы под реально наблюдаемый полёт. Возвращает {a, b, x0, y0, z0, fx, fz, длина} или null. */
    static double[] fit(List<double[]> pts) {
        if (pts.size() < 6) return null;
        List<double[]> d = new ArrayList<>();
        d.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            double[] p = pts.get(i), l = d.get(d.size() - 1);
            double dd = Math.abs(p[0] - l[0]) + Math.abs(p[1] - l[1]) + Math.abs(p[2] - l[2]);
            if (dd > 1e-3) d.add(p);
        }
        if (d.size() < 6) return null;
        double[] f = d.get(0), e = d.get(d.size() - 1);
        double hx = e[0] - f[0], hz = e[2] - f[2];
        double hl = Math.hypot(hx, hz);
        if (hl < 8) return null;
        double fx = hx / hl, fz = hz / hl;
        double s2 = 0, s3 = 0, s4 = 0, sy = 0, s2y = 0;
        for (double[] p : d) {
            double s = (p[0] - f[0]) * fx + (p[2] - f[2]) * fz;
            double y = p[1] - f[1];
            s2 += s * s; s3 += s * s * s; s4 += s * s * s * s;
            sy += s * y; s2y += s * s * y;
        }
        double det = s2 * s4 - s3 * s3;
        if (Math.abs(det) < 1e-9) return null;
        double a = (sy * s4 - s3 * s2y) / det;
        double b = (s2 * s2y - s3 * sy) / det;
        if (b >= 0) return null;
        return new double[]{a, b, f[0], f[1], f[2], fx, fz, hl};
    }

    // Результат по реально летящему снаряду (показывается зелёным)
    private static Result liveRes = null;
    private static long liveTime = 0;

    static void setLive(Result r) {
        liveRes = r;
        liveTime = System.currentTimeMillis();
    }

    private static void drawPath(VertexConsumer vc, MatrixStack.Entry e, Vec3d cam, Result res,
                                 int pr, int pg, int pb, int rr, int rg, int rb) {
        List<Vec3d> pts = new ArrayList<>(res.pts);
        if (res.landing != null) pts.add(res.landing);
        for (int i = 0; i + 1 < pts.size(); i++) {
            seg(vc, e, pts.get(i), pts.get(i + 1), cam, pr, pg, pb, 255);
        }
        if (res.landing != null) {
            Vec3d l = res.landing;
            double gy = l.y + 0.1;
            int segs = 48;
            for (double rad : new double[]{RING_RADIUS, RING_RADIUS - 0.12}) {
                Vec3d prev = null;
                for (int k = 0; k <= segs; k++) {
                    double a = 2 * Math.PI * k / segs;
                    Vec3d cur = new Vec3d(l.x + rad * Math.cos(a), gy, l.z + rad * Math.sin(a));
                    if (prev != null) seg(vc, e, prev, cur, cam, rr, rg, rb, 255);
                    prev = cur;
                }
            }
            seg(vc, e, new Vec3d(l.x - 0.6, gy, l.z), new Vec3d(l.x + 0.6, gy, l.z), cam, rr, rg, rb, 255);
            seg(vc, e, new Vec3d(l.x, gy, l.z - 0.6), new Vec3d(l.x, gy, l.z + 0.6), cam, rr, rg, rb, 255);
            seg(vc, e, new Vec3d(l.x, gy, l.z), new Vec3d(l.x, gy + 8, l.z), cam, rr, rg, rb, 255);
        }
    }

    private static void renderWorld(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        hudText = null;
        liveText = null;
        SiegeAim.frame(mc); // плавный доворот взгляда каждый кадр
        if (!enabled || mc.player == null || mc.world == null) return;
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;
        Vec3d cam = ctx.camera().getPos();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry e = matrices.peek();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        // 1) прицел до выстрела (белая линия, красный круг)
        int w = SiegeRecClient.labelIdx;
        if (w >= 0 && w <= 2) {
            double yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
            Result res = compute(mc, w, yaw, pitch, px, py, pz, WIDE_MARGIN);
            Result thin = compute(mc, w, yaw, pitch, px, py, pz, 0);
            double sp = spreadDeg(w, -pitch);
            Result lo = compute(mc, w, yaw, pitch + sp, px, py, pz, 0);
            Result hi = compute(mc, w, yaw, pitch - sp, px, py, pz, 0);
            String band = "";
            if (lo.landing != null && hi.landing != null) {
                double d1 = Math.hypot(lo.landing.x - px, lo.landing.z - pz);
                double d2 = Math.hypot(hi.landing.x - px, hi.landing.z - pz);
                band = String.format(Locale.ROOT, " | разброс ±%.1f°: %.0f–%.0f бл.", sp, Math.min(d1, d2), Math.max(d1, d2));
                drawMarker(vc, e, cam, lo.landing, 1.0, 3, 255, 170, 40);
                drawMarker(vc, e, cam, hi.landing, 1.0, 3, 255, 170, 40);
            }
            if (res.landing != null) {
                Vec3d l = res.landing;
                double dist = Math.hypot(l.x - px, l.z - pz);
                String extra = "";
                if (thin.landing != null) {
                    double dthin = Math.hypot(thin.landing.x - px, thin.landing.z - pz);
                    if (dthin - dist > 3) extra = String.format(Locale.ROOT, " | ЗАДЕНЕТ препятствие (без запаса было бы %.0f)", dthin);
                }
                hudText = String.format(Locale.ROOT, "%s | падение: %d %d %d | дальность %.0f бл.%s",
                        NAMES[w], (int) Math.floor(l.x), (int) Math.floor(l.y), (int) Math.floor(l.z), dist, extra) + band;
            } else {
                hudText = NAMES[w] + " | падение не видно: " + res.stop;
            }
            drawPath(vc, e, cam, res, 255, 255, 255, 255, 40, 40);
        }

        // 1b) цель доводки (голубой круг, радиус поражения 3 блока)
        if (SiegeAim.markerPos != null) {
            drawMarker(vc, e, cam, SiegeAim.markerPos, SiegeAim.RADIUS, 6, 60, 200, 255);
        }

        // 2) реальный снаряд в полёте: траектория по его настоящему движению (зелёная)
        if (liveRes != null && System.currentTimeMillis() - liveTime < 40000) {
            Result lr = liveRes;
            if (lr.landing != null) {
                Vec3d l = lr.landing;
                liveText = String.format(Locale.ROOT, "Реальный выстрел упадёт: %d %d %d | дальность %.0f бл. от тебя",
                        (int) Math.floor(l.x), (int) Math.floor(l.y), (int) Math.floor(l.z),
                        Math.hypot(l.x - px, l.z - pz));
            }
            drawPath(vc, e, cam, lr, 80, 255, 100, 80, 255, 100);
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
        if (mc.options.hudHidden) return;
        int W = mc.getWindow().getScaledWidth();
        if (hudText != null) {
            int tw = mc.textRenderer.getWidth(hudText);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(hudText), (W - tw) / 2, 18, 0xFFFFFFFF);
        }
        if (liveText != null) {
            int tw = mc.textRenderer.getWidth(liveText);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(liveText), (W - tw) / 2, 30, 0xFF80FF80);
        }
        if (SiegeAim.line1 != null) {
            int tw = mc.textRenderer.getWidth(SiegeAim.line1);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(SiegeAim.line1), (W - tw) / 2, 42, 0xFF60C8FF);
        }
        if (SiegeAim.line2 != null) {
            int tw = mc.textRenderer.getWidth(SiegeAim.line2);
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(SiegeAim.line2), (W - tw) / 2, 54,
                    SiegeAim.warn ? 0xFFFF5050 : 0xFFFFFFFF);
        }
    }
}
