package net.huwng.highv.client.trail;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.huwng.highv.HighV;
import net.huwng.highv.client.SpeedEffectSystem;
import net.huwng.highv.client.animation.DashAnimationHandler;
import net.huwng.highv.client.animation.DriftAnimationHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.*;

/**
 * Hiệu ứng dải lụa gió tốc độ cao (Speed Wind Ribbon Trails).
 *
 * Khác hoàn toàn với hệ thống particle hạt rời rạc thông thường:
 * Đây là dải mesh 3D liên tục (Continuous Ribbon Quad Strips) mô phỏng luồng
 * khí động học (aerodynamic slipstream) xé gió phía sau người chơi — phong
 * cách tương tự các dải Motion Ribbon trong Roblox, Sonic, Warframe và Titanfall.
 *
 * Đặc điểm kỹ thuật:
 *  - 4 dải lụa gió khí động học:
 *      + 2 dải trên (Upper Streamers): Phát ra từ 2 bờ vai/cánh lưng, cuộn dạt
 *        ra phía sau như luồng gió xoáy xé gió của cánh máy bay phản lực.
 *      + 2 dải dưới (Lower Streamers): Phát ra từ 2 gót chân/mắt cá, lướt sát
 *        mặt đất khi trượt/drift hoặc bung ra khi bhop trên không.
 *  - Billboard Ribbon Math: Các mặt quad luôn tự động xoay vuông góc với góc nhìn
 *    camera (Cross product giữa vector hướng di chuyển và vector nhìn), đảm bảo dải
 *    lụa luôn có độ dày đầy đặn từ mọi góc nhìn (thứ nhất, thứ ba, nhìn từ trên/sau).
 *  - Feathered Glow Gradient: Mỗi tiết diện gồm 3 đỉnh (Mép Trái - Lõi Giữa - Mép Phải).
 *    Lõi giữa đạt độ sáng trắng/cyan rực rỡ, hai mép ngoài triệt tiêu về alpha 0
 *    tạo hiệu ứng phát sáng mờ tự nhiên (feathered antialiasing) mà không cần texture.
 *  - Catmull-Rom Spline: Nội suy làm mượt đường cong 3D giữa các điểm lấy mẫu,
 *    giúp dải lụa uốn lượn mượt mà khi người chơi bẻ cua, strafe hoặc bay lượn.
 *  - Tự động co vuốt nhọn về phía đuôi (tapering) và mờ dần theo thời gian sống.
 */
@EventBusSubscriber(modid = HighV.MOD_ID, value = Dist.CLIENT)
public final class SpeedWindTrailRenderer {

    /** Ngưỡng tốc độ tối thiểu để bắt đầu xuất hiện dải lụa gió (>15.0 m/s). */
    private static final double MIN_SPEED_THRESHOLD = 15.0;

    /** Khoảng cách di chuyển tối thiểu giữa 2 điểm mẫu (m) để tạo node mới. */
    private static final double MIN_SAMPLE_DIST_SQ = 0.04;

    /** Số phân đoạn nội suy Catmull-Rom giữa 2 node chính để tạo đường cong mượt mà. */
    private static final int SUBDIVISIONS = 2;

    public enum StreamerType {
        UPPER_LEFT,
        UPPER_RIGHT,
        LOWER_LEFT,
        LOWER_RIGHT
    }

    /** Điểm mốc trên dải lụa gió. */
    private static class TrailNode {
        final Vec3 pos;
        final long timeMs;
        final double baseWidth;
        final long lifetimeMs;
        final boolean isDashing;
        final boolean isDrifting;

        TrailNode(Vec3 pos, long timeMs, double baseWidth, long lifetimeMs, boolean isDashing, boolean isDrifting) {
            this.pos = pos;
            this.timeMs = timeMs;
            this.baseWidth = baseWidth;
            this.lifetimeMs = lifetimeMs;
            this.isDashing = isDashing;
            this.isDrifting = isDrifting;
        }
    }

    /** Lưu trữ lịch sử 4 dải lụa cho mỗi người chơi. */
    private static class PlayerTrailData {
        final Map<StreamerType, LinkedList<TrailNode>> streamers = new EnumMap<>(StreamerType.class);

        PlayerTrailData() {
            for (StreamerType type : StreamerType.values()) {
                streamers.put(type, new LinkedList<>());
            }
        }
    }

    private static final Map<UUID, PlayerTrailData> trails = new HashMap<>();

    private SpeedWindTrailRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !net.huwng.highv.client.HighVClientConfig.ENABLE_WIND_TRAILS.get()) {
            trails.clear();
            return;
        }

        long now = System.currentTimeMillis();
        float partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        // 1. Cập nhật và lấy mẫu dải lụa cho local player và người chơi lân cận
        for (Player p : mc.level.players()) {
            if (p.distanceToSqr(camPos.x, camPos.y, camPos.z) > 64.0 * 64.0) continue;
            updatePlayerTrail(p, now, partialTicks);
        }

        // 2. Dọn dẹp các player đã offline hoặc dữ liệu rỗng
        cleanupExpiredTrails(now);

        // 3. Render toàn bộ dải lụa gió 3D trong thế giới
        renderAllTrails(event.getPoseStack(), camPos, now, mc);
    }

    /**
     * Cập nhật và lấy mẫu vị trí neo (anchors) của 4 dải lụa cho người chơi.
     */
    private static void updatePlayerTrail(Player player, long now, float partialTicks) {
        UUID id = player.getUUID();
        PlayerTrailData data = trails.computeIfAbsent(id, k -> new PlayerTrailData());

        Vec3 vel = player.getDeltaMovement();
        double speedBs = Math.sqrt(vel.x * vel.x + vel.z * vel.z) * 20.0;
        if (player == Minecraft.getInstance().player && SpeedEffectSystem.rawSpeed > speedBs) {
            speedBs = SpeedEffectSystem.rawSpeed;
        }

        boolean isDashing = DashAnimationHandler.isActive(id);
        boolean isDrifting = DriftAnimationHandler.isSyncedSliding(id);
        boolean shouldEmit = speedBs >= MIN_SPEED_THRESHOLD;

        // Tính toán độ rộng và thời gian sống dải lụa gió (thanh thoát, ngắn gọn, khí động học)
        double speedRatio = Math.min(1.0, Math.max(0.0, (speedBs - MIN_SPEED_THRESHOLD) / 25.0));
        // Độ rộng mỏng, thanh mảnh (0.04m -> 0.09m, dash ~0.11m) thay vì to đậm như trước
        double baseWidth = 0.04 + speedRatio * 0.05;
        // Thời gian sống ngắn (130ms -> 230ms, dash ~260ms) để dải gió ôm sát sau lưng, không bị kéo dài lê thê
        long lifetimeMs = (long) (130 + speedRatio * 100);

        if (isDashing) {
            baseWidth *= 1.25;
            lifetimeMs = Math.max(lifetimeMs, 250);
        }

        for (StreamerType type : StreamerType.values()) {
            LinkedList<TrailNode> list = data.streamers.get(type);

            // Bỏ các node đã hết hạn
            while (!list.isEmpty() && (now - list.peekLast().timeMs) > list.peekLast().lifetimeMs) {
                list.removeLast();
            }

            // Kiểm tra có phát sinh dải lụa này không
            boolean isLower = (type == StreamerType.LOWER_LEFT || type == StreamerType.LOWER_RIGHT);
            if (isLower && !isDrifting && !player.onGround() && !isDashing && speedBs < 12.0) {
                // Dải dưới chân chỉ phát khi chạm đất tốc độ cao, trượt drift, hoặc dash
                continue;
            }

            if (!shouldEmit) continue;

            Vec3 anchor = computeAnchorPos(player, type, partialTicks);
            if (list.isEmpty()) {
                list.addFirst(new TrailNode(anchor, now, baseWidth, lifetimeMs, isDashing, isDrifting));
            } else {
                TrailNode head = list.peekFirst();
                double distSq = anchor.distanceToSqr(head.pos);
                if (distSq > 8.0 * 8.0) {
                    // Dịch chuyển tức thời (teleport / respawn) -> xóa bỏ dải cũ để tránh bị kéo dài ngang bản đồ
                    list.clear();
                    list.addFirst(new TrailNode(anchor, now, baseWidth, lifetimeMs, isDashing, isDrifting));
                } else if (distSq >= MIN_SAMPLE_DIST_SQ) {
                    list.addFirst(new TrailNode(anchor, now, baseWidth, lifetimeMs, isDashing, isDrifting));
                }
            }
        }
    }

    /**
     * Tính toán vị trí neo 3D bám trên cơ thể người chơi (vai hoặc gót chân).
     */
    private static Vec3 computeAnchorPos(Player player, StreamerType type, float partialTicks) {
        double px = Mth.lerp(partialTicks, player.xOld, player.getX());
        double py = Mth.lerp(partialTicks, player.yOld, player.getY());
        double pz = Mth.lerp(partialTicks, player.zOld, player.getZ());
        Vec3 playerPos = new Vec3(px, py, pz);

        Minecraft mc = Minecraft.getInstance();
        boolean isLocalFirstPerson = (player == mc.player && mc.options.getCameraType().isFirstPerson());

        float yaw = isLocalFirstPerson ? player.getViewYRot(partialTicks) : Mth.rotLerp(partialTicks, player.yBodyRotO, player.yBodyRot);
        double yawRad = Math.toRadians(yaw);

        double forwardX = -Math.sin(yawRad);
        double forwardZ =  Math.cos(yawRad);
        double rightX   =  Math.cos(yawRad);
        double rightZ   =  Math.sin(yawRad);

        double height = player.getBbHeight();

        return switch (type) {
            case UPPER_LEFT -> {
                double side = isLocalFirstPerson ? -0.36 : -0.26;
                double back = isLocalFirstPerson ? -0.05 : -0.12;
                double yOffset = isLocalFirstPerson ? 1.05 : (height * 0.65);
                yield playerPos.add(rightX * side + forwardX * back, yOffset, rightZ * side + forwardZ * back);
            }
            case UPPER_RIGHT -> {
                double side = isLocalFirstPerson ? 0.36 : 0.26;
                double back = isLocalFirstPerson ? -0.05 : -0.12;
                double yOffset = isLocalFirstPerson ? 1.05 : (height * 0.65);
                yield playerPos.add(rightX * side + forwardX * back, yOffset, rightZ * side + forwardZ * back);
            }
            case LOWER_LEFT -> {
                double side = -0.16;
                double back = -0.08;
                double yOffset = 0.12;
                yield playerPos.add(rightX * side + forwardX * back, yOffset, rightZ * side + forwardZ * back);
            }
            case LOWER_RIGHT -> {
                double side = 0.16;
                double back = -0.08;
                double yOffset = 0.12;
                yield playerPos.add(rightX * side + forwardX * back, yOffset, rightZ * side + forwardZ * back);
            }
        };
    }

    private static void cleanupExpiredTrails(long now) {
        trails.entrySet().removeIf(entry -> {
            PlayerTrailData data = entry.getValue();
            boolean allEmpty = true;
            for (LinkedList<TrailNode> list : data.streamers.values()) {
                while (!list.isEmpty() && (now - list.peekLast().timeMs) > list.peekLast().lifetimeMs) {
                    list.removeLast();
                }
                if (!list.isEmpty()) allEmpty = false;
            }
            return allEmpty;
        });
    }

    /**
     * Render toàn bộ dải lụa bằng Mesh Quad Strip với Billboard Camera-Facing.
     */
    private static void renderAllTrails(PoseStack pose, Vec3 camPos, long now, Minecraft mc) {
        if (trails.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder bb = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f mat = pose.last().pose();

        for (Map.Entry<UUID, PlayerTrailData> entry : trails.entrySet()) {
            PlayerTrailData data = entry.getValue();
            for (Map.Entry<StreamerType, LinkedList<TrailNode>> sEntry : data.streamers.entrySet()) {
                LinkedList<TrailNode> nodes = sEntry.getValue();
                if (nodes.size() < 2) continue;

                renderSingleStreamer(bb, mat, nodes, camPos, now);
            }
        }

        MeshData mesh = bb.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /**
     * Render một dải lụa đơn lẻ với nội suy Catmull-Rom và dải màu Feathered Glow.
     */
    private static void renderSingleStreamer(BufferBuilder bb, Matrix4f mat, List<TrailNode> nodes, Vec3 camPos, long now) {
        int count = nodes.size();
        if (count < 2) return;

        // Sinh danh sách các điểm đã làm mượt bằng spline
        List<InterpolatedPoint> points = generateSmoothSpline(nodes, now);
        if (points.size() < 2) return;

        // Tính toán các vector tiết diện camera-facing
        for (int i = 0; i < points.size() - 1; i++) {
            InterpolatedPoint p0 = points.get(i);
            InterpolatedPoint p1 = points.get(i + 1);

            // Tọa độ tương đối so với camera (tránh jitter số thực lớn)
            float rx0 = (float) (p0.pos.x - camPos.x);
            float ry0 = (float) (p0.pos.y - camPos.y);
            float rz0 = (float) (p0.pos.z - camPos.z);

            float rx1 = (float) (p1.pos.x - camPos.x);
            float ry1 = (float) (p1.pos.y - camPos.y);
            float rz1 = (float) (p1.pos.z - camPos.z);

            // Vector vuông góc mặt nón (width vector)
            float wx0 = (float) p0.widthVec.x;
            float wy0 = (float) p0.widthVec.y;
            float wz0 = (float) p0.widthVec.z;

            float wx1 = (float) p1.widthVec.x;
            float wy1 = (float) p1.widthVec.y;
            float wz1 = (float) p1.widthVec.z;

            // Màu sắc và Alpha
            int alphaCore0 = (int) (p0.alpha * 255);
            int alphaCore1 = (int) (p1.alpha * 255);
            int alphaEdge0 = 0; // Mép ngoài feather về 0
            int alphaEdge1 = 0;

            // Quad 1: Cánh Trái (Mép Trái -> Lõi Giữa)
            // Đỉnh 1: L0
            bb.addVertex(mat, rx0 - wx0, ry0 - wy0, rz0 - wz0).setColor(p0.edgeR, p0.edgeG, p0.edgeB, alphaEdge0);
            // Đỉnh 2: C0
            bb.addVertex(mat, rx0, ry0, rz0).setColor(p0.coreR, p0.coreG, p0.coreB, alphaCore0);
            // Đỉnh 3: C1
            bb.addVertex(mat, rx1, ry1, rz1).setColor(p1.coreR, p1.coreG, p1.coreB, alphaCore1);
            // Đỉnh 4: L1
            bb.addVertex(mat, rx1 - wx1, ry1 - wy1, rz1 - wz1).setColor(p1.edgeR, p1.edgeG, p1.edgeB, alphaEdge1);

            // Quad 2: Cánh Phải (Lõi Giữa -> Mép Phải)
            // Đỉnh 1: C0
            bb.addVertex(mat, rx0, ry0, rz0).setColor(p0.coreR, p0.coreG, p0.coreB, alphaCore0);
            // Đỉnh 2: R0
            bb.addVertex(mat, rx0 + wx0, ry0 + wy0, rz0 + wz0).setColor(p0.edgeR, p0.edgeG, p0.edgeB, alphaEdge0);
            // Đỉnh 3: R1
            bb.addVertex(mat, rx1 + wx1, ry1 + wy1, rz1 + wz1).setColor(p1.edgeR, p1.edgeG, p1.edgeB, alphaEdge1);
            // Đỉnh 4: C1
            bb.addVertex(mat, rx1, ry1, rz1).setColor(p1.coreR, p1.coreG, p1.coreB, alphaCore1);
        }
    }

    private static class InterpolatedPoint {
        Vec3 pos;
        Vec3 widthVec;
        float alpha;
        int coreR, coreG, coreB;
        int edgeR, edgeG, edgeB;
    }

    /**
     * Tạo chuỗi điểm mượt bằng Catmull-Rom spline và tính toán vector chiều rộng Billboard.
     */
    private static List<InterpolatedPoint> generateSmoothSpline(List<TrailNode> nodes, long now) {
        List<InterpolatedPoint> result = new ArrayList<>();
        int n = nodes.size();

        for (int i = 0; i < n - 1; i++) {
            TrailNode p0 = (i > 0) ? nodes.get(i - 1) : nodes.get(i);
            TrailNode p1 = nodes.get(i);
            TrailNode p2 = nodes.get(i + 1);
            TrailNode p3 = (i + 2 < n) ? nodes.get(i + 2) : p2;

            int steps = (i == 0) ? SUBDIVISIONS + 1 : SUBDIVISIONS;
            for (int step = 0; step < steps; step++) {
                double t = (double) step / (double) steps;
                Vec3 curvePos = catmullRom(p0.pos, p1.pos, p2.pos, p3.pos, t);

                // Nội suy thời gian và thông số
                double nodeTime = Mth.lerp(t, p1.timeMs, p2.timeMs);
                double baseW = Mth.lerp(t, p1.baseWidth, p2.baseWidth);
                double lifetime = Mth.lerp(t, p1.lifetimeMs, p2.lifetimeMs);
                boolean isDashing = p1.isDashing || p2.isDashing;

                double elapsed = Math.max(0, now - nodeTime);
                float progress = (float) Math.min(1.0, elapsed / lifetime);

                // Tapering: Vuốt nhọn dần về đuôi (0.0 ở chóp đuôi)
                float width = (float) (baseW * Math.pow(1.0f - progress, 0.85));

                // Alpha: Mờ dần nhẹ nhàng, độ trong suốt mô phỏng luồng gió tự nhiên (không đặc quánh)
                float alpha = (float) (Math.pow(1.0f - progress, 1.4) * (isDashing ? 0.38f : 0.26f));

                InterpolatedPoint pt = new InterpolatedPoint();
                pt.pos = curvePos;
                pt.alpha = alpha;

                // Màu sắc: Trắng tinh khiết tự nhiên của gió (Pure Air Stream White), loại bỏ hoàn toàn viền cyan neon
                pt.coreR = 255; pt.coreG = 255; pt.coreB = 255;
                pt.edgeR = 255; pt.edgeG = 255; pt.edgeB = 255;

                pt.widthVec = new Vec3(0, width * 0.5, 0); // Tạm gán, sẽ tính billboard bên dưới
                result.add(pt);
            }
        }

        // Thêm node cuối cùng
        TrailNode lastNode = nodes.get(n - 1);
        double lastElapsed = Math.max(0, now - lastNode.timeMs);
        float lastProgress = (float) Math.min(1.0, lastElapsed / lastNode.lifetimeMs);
        InterpolatedPoint endPt = new InterpolatedPoint();
        endPt.pos = lastNode.pos;
        endPt.alpha = 0.0f; // Chóp đuôi triệt tiêu hoàn toàn
        endPt.coreR = 255; endPt.coreG = 255; endPt.coreB = 255;
        endPt.edgeR = 255; endPt.edgeG = 255; endPt.edgeB = 255;
        endPt.widthVec = Vec3.ZERO;
        result.add(endPt);

        // Tính vector vuông góc Billboard (Camera-facing Ribbon Normal)
        Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();

        for (int k = 0; k < result.size(); k++) {
            InterpolatedPoint pt = result.get(k);

            // Vector tiếp tuyến hướng đi của dải lụa (tangent)
            Vec3 tangent;
            if (k == 0 && result.size() > 1) {
                tangent = result.get(1).pos.subtract(pt.pos);
            } else if (k == result.size() - 1 && result.size() > 1) {
                tangent = pt.pos.subtract(result.get(k - 1).pos);
            } else if (result.size() > 2) {
                tangent = result.get(k + 1).pos.subtract(result.get(k - 1).pos);
            } else {
                tangent = new Vec3(0, 0, 1);
            }

            // Vector nhìn từ camera tới điểm trên dải lụa
            Vec3 viewVec = pt.pos.subtract(camPos);

            // Vector pháp tuyến vuông góc cả hướng đi lẫn hướng nhìn (Cross Product)
            Vec3 normal = tangent.cross(viewVec);
            double len = normal.length();
            if (len > 1.0e-5) {
                normal = normal.scale(1.0 / len);
            } else {
                normal = new Vec3(0, 1, 0);
            }

            double halfWidth = pt.widthVec.y; // Lưu tạm độ rộng ở bước trên

            // Thao tác gợn sóng khí động học rất nhỏ (Aerodynamic air ripple)
            double wave = Math.sin(now * 0.012 + k * 0.45) * 0.003 * (1.0 - pt.alpha);
            pt.widthVec = normal.scale(Math.max(0.001, halfWidth + wave));
        }

        return result;
    }

    /**
     * Công thức nội suy Catmull-Rom Spline 3D mượt mà.
     */
    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2.0 * p1.x) +
                (-p0.x + p2.x) * t +
                (2.0 * p0.x - 5.0 * p1.x + 4.0 * p2.x - p3.x) * t2 +
                (-p0.x + 3.0 * p1.x - 3.0 * p2.x + p3.x) * t3);

        double y = 0.5 * ((2.0 * p1.y) +
                (-p0.y + p2.y) * t +
                (2.0 * p0.y - 5.0 * p1.y + 4.0 * p2.y - p3.y) * t2 +
                (-p0.y + 3.0 * p1.y - 3.0 * p2.y + p3.y) * t3);

        double z = 0.5 * ((2.0 * p1.z) +
                (-p0.z + p2.z) * t +
                (2.0 * p0.z - 5.0 * p1.z + 4.0 * p2.z - p3.z) * t2 +
                (-p0.z + 3.0 * p1.z - 3.0 * p2.z + p3.z) * t3);

        return new Vec3(x, y, z);
    }
}
