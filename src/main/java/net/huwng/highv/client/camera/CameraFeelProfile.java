package net.huwng.highv.client.camera;

/**
 * Bộ tham số camera-feel cho một trạng thái di chuyển cụ thể (đi bộ, chạy,
 * bơi, bay, cưỡi thú, ngồi phương tiện...). Mỗi trạng thái có "độ nghiêng"
 * và "độ mượt" riêng để cảm giác camera khác nhau tùy theo bối cảnh.
 *
 * Đây là data record thuần, KHÔNG chứa state theo thời gian (state theo
 * thời gian nằm ở {@link CameraFeelState}).
 */
public final class CameraFeelProfile {

    /** Hệ số roll (nghiêng camera) theo velocity ngang khi strafe trái/phải. */
    public double strafeRollFactor;

    /** Hệ số pitch (ngẩng/cúi) theo velocity theo hướng nhìn (tiến/lùi). */
    public double forwardPitchFactor;

    /** Hệ số pitch theo velocity trục Y (rơi/nhảy). */
    public double verticalPitchFactor;

    /** Hệ số làm mượt cho các offset theo velocity ngang (strafe/forward). */
    public double horizontalSmoothingMultiplier;

    /** Hệ số làm mượt cho offset theo velocity dọc (rơi/nhảy). */
    public double verticalSmoothingMultiplier;

    /**
     * Độ trễ chuột (mouse smoothing) — 0 = tắt hoàn toàn, giá trị càng cao
     * thì camera càng "lướt" theo sau chuyển động chuột thay vì bám sát
     * ngay lập tức. Dùng cho cảm giác bơi/bay chậm rãi hơn.
     */
    public double mouseSmoothing;

    public CameraFeelProfile(
        double strafeRollFactor,
        double forwardPitchFactor,
        double verticalPitchFactor,
        double horizontalSmoothingMultiplier,
        double verticalSmoothingMultiplier,
        double mouseSmoothing
    ) {
        this.strafeRollFactor = strafeRollFactor;
        this.forwardPitchFactor = forwardPitchFactor;
        this.verticalPitchFactor = verticalPitchFactor;
        this.horizontalSmoothingMultiplier = horizontalSmoothingMultiplier;
        this.verticalSmoothingMultiplier = verticalSmoothingMultiplier;
        this.mouseSmoothing = mouseSmoothing;
    }

    /** Tạo bản sao rỗng để dùng làm buffer nội suy (tránh alloc mỗi tick). */
    public static CameraFeelProfile blank() {
        return new CameraFeelProfile(0, 0, 0, 1, 1, 0);
    }

    /** Nội suy tuyến tính từng field giữa hai profile, ghi kết quả vào {@code this}. */
    public void lerpFrom(CameraFeelProfile from, CameraFeelProfile to, double t) {
        double clamped = t < 0 ? 0 : (t > 1 ? 1 : t);
        strafeRollFactor = from.strafeRollFactor + (to.strafeRollFactor - from.strafeRollFactor) * clamped;
        forwardPitchFactor = from.forwardPitchFactor + (to.forwardPitchFactor - from.forwardPitchFactor) * clamped;
        verticalPitchFactor = from.verticalPitchFactor + (to.verticalPitchFactor - from.verticalPitchFactor) * clamped;
        horizontalSmoothingMultiplier = from.horizontalSmoothingMultiplier + (to.horizontalSmoothingMultiplier - from.horizontalSmoothingMultiplier) * clamped;
        verticalSmoothingMultiplier = from.verticalSmoothingMultiplier + (to.verticalSmoothingMultiplier - from.verticalSmoothingMultiplier) * clamped;
        mouseSmoothing = from.mouseSmoothing + (to.mouseSmoothing - from.mouseSmoothing) * clamped;
    }

    // ------------------------------------------------------------------
    // Bộ giá trị mặc định theo từng trạng thái. Chỉnh trực tiếp ở đây khi
    // cần tinh chỉnh cảm giác — chưa có config screen, giai đoạn tới sẽ báo
    // lại những gì cần đổi.
    // ------------------------------------------------------------------

    public static CameraFeelProfile walking() {
        return new CameraFeelProfile(10.0, 7.0, 2.5, 1.0, 1.0, 0.0);
    }

    public static CameraFeelProfile sprinting() {
        return new CameraFeelProfile(12.5, 9.5, 2.5, 1.0, 1.0, 0.6);
    }

    public static CameraFeelProfile swimming() {
        return new CameraFeelProfile(-30.0, 21.0, 7.5, 1.0, 1.0, 1.5);
    }

    public static CameraFeelProfile flying() {
        return new CameraFeelProfile(-10.0, 7.0, 2.5, 1.0, 1.0, 1.0);
    }

    public static CameraFeelProfile mounted() {
        return new CameraFeelProfile(20.0, 3.5, 2.5, 1.0, 1.0, 1.0);
    }

    public static CameraFeelProfile vehicle() {
        return new CameraFeelProfile(5.0, 3.5, 5.0, 1.0, 1.0, 0.0);
    }
}
