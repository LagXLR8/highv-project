package net.huwng.highv.client.renderer.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.huwng.highv.HighV;
import net.huwng.highv.entity.GrapplingHookEntity;
import net.huwng.highv.client.GrapplingArmAnimator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Render grappling hook entity — dây quad-tube đen.
 *
 * ── Fix dây bị ngắt khi bay ──
 * EntityRenderer.render() nhận PoseStack đã translate đến entity position.
 * Tất cả tọa độ phải tính RELATIVE TO ENTITY POSITION.
 *
 * ── Entity hook ──
 * Khi hook.getHookedEntityId() >= 0, điểm B (đầu dây phía hook) được dịch
 * đến center của entity đó thay vì dùng (0,0,0) — tránh dây "đứng yên"
 * trong khi entity đang di chuyển.
 */
public class GrapplingHookRenderer extends EntityRenderer<GrapplingHookEntity> {

    private static final ResourceLocation ROPE_TEXTURE =
            HighV.id("textures/entity/rope.png");
    private static final RenderType ROPE_RENDER =
            RenderType.entitySolid(ROPE_TEXTURE);

    private static final float ROPE_RADIUS = 0.025f;
    private static final int   CR = 25, CG = 25, CB = 25; // đen

    private static final int   SEGMENTS     = 20;
    private static final float HELIX_RADIUS = 0.12f;
    private static final float HELIX_TURNS  = 1.0f;
    private static final int   SETTLE_TICKS = 8;

    public GrapplingHookRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    public boolean shouldRenderAtSqrDistance(GrapplingHookEntity entity, double distSq) {
        return true;
    }

    @Override
    public void render(GrapplingHookEntity hook, float yaw, float pt,
                       PoseStack pose, MultiBufferSource buf, int light) {
        if (!(hook.getOwner() instanceof Player player)) return;

        // Interpolated entity world pos (= vị trí hook hiện tại trong world)
        double eX = lerp(hook.xo != 0 ? hook.xo : hook.getX(), hook.getX(), pt);
        double eY = lerp(hook.yo != 0 ? hook.yo : hook.getY(), hook.getY(), pt);
        double eZ = lerp(hook.zo != 0 ? hook.zo : hook.getZ(), hook.getZ(), pt);

        // ── Điểm B: đầu dây phía hook ────────────────────────────────────────
        // Khi bám entity, entity có thể đang di chuyển → lấy vị trí thực tế của entity
        double bx = 0, by = 0, bz = 0;
        int hookedId = hook.getHookedEntityId();
        if (hookedId >= 0 && Minecraft.getInstance().level != null) {
            Entity hookedEnt = Minecraft.getInstance().level.getEntity(hookedId);
            if (hookedEnt != null) {
                // Center của entity (world space) → chuyển về pose space (relative to hook)
                double hentX = lerp(hookedEnt.xo != 0 ? hookedEnt.xo : hookedEnt.getX(), hookedEnt.getX(), pt);
                double hentY = lerp(hookedEnt.yo != 0 ? hookedEnt.yo : hookedEnt.getY(), hookedEnt.getY(), pt) + hookedEnt.getBbHeight() * 0.5;
                double hentZ = lerp(hookedEnt.zo != 0 ? hookedEnt.zo : hookedEnt.getZ(), hookedEnt.getZ(), pt);
                bx = hentX - eX;
                by = hentY - eY;
                bz = hentZ - eZ;
            }
        }

        // ── Điểm A: bám chuẩn xác vào bàn tay trái player trong 3D world space ─────────────────────
        double ax, ay, az;
        Minecraft mc = Minecraft.getInstance();
        boolean isLocalFirstPerson = (player == mc.player && mc.options.getCameraType().isFirstPerson());

        double px = lerp(player.xo != 0 ? player.xo : player.getX(), player.getX(), pt);
        double py = lerp(player.yo != 0 ? player.yo : player.getY(), player.getY(), pt);
        double pz = lerp(player.zo != 0 ? player.zo : player.getZ(), player.getZ(), pt);

        // Ở FPP, vai trái xoay đồng bộ theo hướng nhìn camera (getViewYRot); ở TPP xoay theo yBodyRot
        double bodyYaw = isLocalFirstPerson ? player.getViewYRot(pt) : lerpAngle(player.yBodyRotO, player.yBodyRot, pt);
        double bodyYawRad = Math.toRadians(bodyYaw);

        double cosYaw = Math.cos(bodyYawRad);
        double sinYaw = Math.sin(bodyYawRad);

        // Khớp vai trái của người chơi: nằm ở bên trái (+Left vector * 0.3125)
        double shSide = 0.3125;
        double shHeight = player.isCrouching() ? 1.15 : 1.375;
        double fwdDist = isLocalFirstPerson ? 0.22 : 0.0;
        double upOffset = isLocalFirstPerson ? 0.10 : 0.0;

        double fwdX = -sinYaw * fwdDist;
        double fwdZ =  cosYaw * fwdDist;

        double shX = px + cosYaw * shSide + fwdX;
        double shY = py + shHeight + upOffset;
        double shZ = pz + sinYaw * shSide + fwdZ;

        // Target hook position in world space
        double targetX = eX + bx;
        double targetY = eY + by;
        double targetZ = eZ + bz;

        // Vector từ khớp vai tới hook
        double armDx = targetX - shX;
        double armDy = targetY - shY;
        double armDz = targetZ - shZ;
        double armLen = Math.sqrt(armDx * armDx + armDy * armDy + armDz * armDz);

        Vec3 vel = hook.getDeltaMovement();
        double velLen = vel.length();
        Vec3 shootDir = (velLen > 0.1) ? vel.scale(1.0 / velLen) : player.getLookAngle();

        // Đồng bộ thuật toán tránh giật cánh tay với PlayerModelMixin khi vừa bắn
        if (!hook.isAttached() && armLen < 3.0) {
            double blend = Math.max(0.0, Math.min(1.0, (armLen - 0.4) / 2.6));
            armDx = Mth.lerp(blend, shootDir.x, armDx / Math.max(1e-4, armLen));
            armDy = Mth.lerp(blend, shootDir.y, armDy / Math.max(1e-4, armLen));
            armDz = Mth.lerp(blend, shootDir.z, armDz / Math.max(1e-4, armLen));
            double newLen = Math.sqrt(armDx * armDx + armDy * armDy + armDz * armDz);
            if (newLen > 1e-4) {
                armDx /= newLen;
                armDy /= newLen;
                armDz /= newLen;
            }
        } else if (armLen > 0.05) {
            armDx /= armLen;
            armDy /= armLen;
            armDz /= armLen;
        } else {
            armDx = shootDir.x;
            armDy = shootDir.y;
            armDz = shootDir.z;
        }

        // Chiều dài cánh tay trái: TPP ~ 0.58m; FPP được scale 0.82x (~0.475m) cho thon gọn cân đối
        double ARM_LENGTH = isLocalFirstPerson ? (0.58 * 0.82) : 0.58;
        double handX = shX + armDx * ARM_LENGTH;
        double handY = shY + armDy * ARM_LENGTH;
        double handZ = shZ + armDz * ARM_LENGTH;

        ax = handX - eX;
        ay = handY - eY;
        az = handZ - eZ;

        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (len < 0.05) return;

        // ── Twist factor ──────────────────────────────────────────────────────
        float twist = hook.isAttached()
                ? Math.max(0f, 1f - (float) hook.getSettleTick() / SETTLE_TICKS)
                : 1.0f;
        float phase = hook.getLifetimeTicks() * 0.4f;

        // ── Perp vectors ──────────────────────────────────────────────────────
        double ux = dx/len, uy = dy/len, uz = dz/len;
        double[] pA = Math.abs(uy) < 0.99
                ? norm(cross(ux, uy, uz, 0, 1, 0))
                : norm(cross(ux, uy, uz, 1, 0, 0));
        double[] pB = norm(cross(ux, uy, uz, pA[0], pA[1], pA[2]));

        // ── Điểm trung tâm helix ──────────────────────────────────────────────
        int     N  = SEGMENTS + 1;
        float[] cx = new float[N], cy = new float[N], cz = new float[N];
        for (int i = 0; i < N; i++) {
            float t   = (float) i / SEGMENTS;
            float ang = (float)(t * HELIX_TURNS * 2 * Math.PI) + phase;
            float env = (float) Math.sin(t * Math.PI) * HELIX_RADIUS * twist;
            cx[i] = (float)(ax + dx*t + (Math.cos(ang)*pA[0] + Math.sin(ang)*pB[0]) * env);
            cy[i] = (float)(ay + dy*t + (Math.cos(ang)*pA[1] + Math.sin(ang)*pB[1]) * env);
            cz[i] = (float)(az + dz*t + (Math.cos(ang)*pA[2] + Math.sin(ang)*pB[2]) * env);
        }

        // ── Render quad-tube ──────────────────────────────────────────────────
        pose.pushPose();
        VertexConsumer  vc   = buf.getBuffer(ROPE_RENDER);
        Matrix4f        m4   = pose.last().pose();
        PoseStack.Pose  last = pose.last();

        for (int i = 0; i < SEGMENTS; i++) {
            float s0x = cx[i],   s0y = cy[i],   s0z = cz[i];
            float s1x = cx[i+1], s1y = cy[i+1], s1z = cz[i+1];

            float fwx = s1x-s0x, fwy = s1y-s0y, fwz = s1z-s0z;
            float fl  = (float) Math.sqrt(fwx*fwx + fwy*fwy + fwz*fwz);
            if (fl < 1e-6f) continue;
            fwx/=fl; fwy/=fl; fwz/=fl;

            double[] qa = Math.abs(fwy) < 0.99
                    ? norm(cross(fwx,fwy,fwz, 0,1,0))
                    : norm(cross(fwx,fwy,fwz, 1,0,0));
            double[] qb = norm(cross(fwx,fwy,fwz, qa[0],qa[1],qa[2]));

            float qax=(float)(qa[0]*ROPE_RADIUS), qay=(float)(qa[1]*ROPE_RADIUS), qaz=(float)(qa[2]*ROPE_RADIUS);
            float qbx=(float)(qb[0]*ROPE_RADIUS), qby=(float)(qb[1]*ROPE_RADIUS), qbz=(float)(qb[2]*ROPE_RADIUS);

            float[][] corners = {
                { qax+qbx, qay+qby, qaz+qbz },
                { qax-qbx, qay-qby, qaz-qbz },
                {-qax-qbx,-qay-qby,-qaz-qbz },
                {-qax+qbx,-qay+qby,-qaz+qbz }
            };

            for (int f = 0; f < 4; f++) {
                float[] c1 = corners[f], c2 = corners[(f+1)%4];
                float nx=(c1[0]+c2[0]), ny=(c1[1]+c2[1]), nz=(c1[2]+c2[2]);
                float nl=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                if (nl>1e-6f){nx/=nl;ny/=nl;nz/=nl;}

                quad(vc, m4, last, light,
                     s0x+c1[0],s0y+c1[1],s0z+c1[2],
                     s0x+c2[0],s0y+c2[1],s0z+c2[2],
                     s1x+c2[0],s1y+c2[1],s1z+c2[2],
                     s1x+c1[0],s1y+c1[1],s1z+c1[2],
                     nx,ny,nz);
            }
        }
        pose.popPose();
    }

    private static void quad(VertexConsumer vc, Matrix4f m4, PoseStack.Pose last, int light,
                              float x0,float y0,float z0, float x1,float y1,float z1,
                              float x2,float y2,float z2, float x3,float y3,float z3,
                              float nx,float ny,float nz) {
        vc.addVertex(m4,x0,y0,z0).setColor(CR,CG,CB,255).setUv(0,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last,nx,ny,nz);
        vc.addVertex(m4,x1,y1,z1).setColor(CR,CG,CB,255).setUv(1,0)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last,nx,ny,nz);
        vc.addVertex(m4,x2,y2,z2).setColor(CR,CG,CB,255).setUv(1,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last,nx,ny,nz);
        vc.addVertex(m4,x3,y3,z3).setColor(CR,CG,CB,255).setUv(0,1)
          .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last,nx,ny,nz);
    }

    private static double lerp(double a, double b, float t) { return a+(b-a)*t; }
    private static double lerpAngle(float a, float b, float t) {
        double d=b-a; while(d>180)d-=360; while(d<-180)d+=360; return a+d*t;
    }
    private static double[] cross(double ax,double ay,double az,double bx,double by,double bz){
        return new double[]{ay*bz-az*by, az*bx-ax*bz, ax*by-ay*bx};
    }
    private static double[] norm(double[] v){
        double l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);
        return l<1e-9?new double[]{1,0,0}:new double[]{v[0]/l,v[1]/l,v[2]/l};
    }

    @Override
    public ResourceLocation getTextureLocation(GrapplingHookEntity e) { return ROPE_TEXTURE; }
}
