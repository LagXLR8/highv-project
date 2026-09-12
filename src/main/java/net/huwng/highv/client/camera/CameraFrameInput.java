package net.huwng.highv.client.camera;

import org.joml.Vector3d;

/**
 * "Chụp" lại toàn bộ thông tin cần thiết cho một lần cập nhật camera-feel:
 * vận tốc entity, góc quay hiện tại, và các cờ trạng thái (bơi/bay/chạy...).
 * Được tạo mới mỗi frame ngay trong mixin và đưa vào {@link CameraFeelState}.
 */
public final class CameraFrameInput {

    public enum Perspective {
        FIRST_PERSON,
        THIRD_PERSON,
        THIRD_PERSON_MIRRORED
    }

    public final Vector3d velocity;
    /** x = pitch, y = yaw, z = luôn 0 (roll chưa tồn tại ở camera vanilla). */
    public final Vector3d rotation;
    public Perspective perspective = Perspective.FIRST_PERSON;

    public boolean sprinting;
    public boolean swimming;
    public boolean flying;
    public boolean ridingMount;
    public boolean ridingVehicle;

    public CameraFrameInput(Vector3d velocity, double pitch, double yaw) {
        this.velocity = velocity;
        this.rotation = new Vector3d(pitch, yaw, 0.0);
    }

    /**
     * Vận tốc quy chiếu theo hướng nhìn hiện tại: x = trái/phải (strafe),
     * y = lên/xuống, z = tiến/lùi. Dùng để tách velocity thế giới thành
     * thành phần "theo hướng camera" cho pitch/roll.
     */
    public Vector3d getViewRelativeVelocity() {
        double yawRad = Math.toRadians(360.0 - rotation.y);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);
        double relX = (cos * velocity.x) - (sin * velocity.z);
        double relZ = (sin * velocity.x) + (cos * velocity.z);
        return new Vector3d(relX, velocity.y, relZ);
    }
}
