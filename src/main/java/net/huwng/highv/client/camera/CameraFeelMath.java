package net.huwng.highv.client.camera;

/**
 * Vài hàm toán tiện ích cho camera feel: smoothing không phụ thuộc framerate,
 * và xử lý góc quay (wrap/unwrap 360 độ) để tránh giật khi góc đi qua biên
 * -180/180.
 */
public final class CameraFeelMath {
    private CameraFeelMath() {}

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    /**
     * "Damping" không phụ thuộc framerate — cùng công thức chuẩn được dùng
     * rộng rãi cho smoothing kiểu exponential-decay
     * (xem: Rory Driscoll, "Frame Rate Independent Damping Using Lerp", 2016).
     * Trả về hệ số lerp cho một khung hình có độ dài {@code dt}, với
     * {@code halfLife}-kiểu tham số là {@code smoothing}.
     */
    public static double dampStep(double smoothing, double dt) {
        return 1.0 - Math.pow(smoothing * smoothing, dt);
    }

    public static double damp(double current, double target, double smoothing, double dt) {
        return lerp(current, target, dampStep(smoothing, dt));
    }

    public static double stepTowards(double current, double target, double step) {
        if (current < target) return Math.min(current + step, target);
        if (current > target) return Math.max(current - step, target);
        return current;
    }

    /** Đưa một bước góc (delta giữa 2 khung hình) về khoảng (-180, 180]. */
    public static double wrapAngleDelta(double degrees) {
        double d = degrees % 360.0;
        if (d <= -180.0) d += 360.0;
        else if (d > 180.0) d -= 360.0;
        return d;
    }

    /** Easing ease-in-out-cubic, dùng để bo cong phản hồi roll khi rẽ. */
    public static double easeInOutCubic(double x) {
        return x < 0.5 ? (4 * x * x * x) : (1 - Math.pow(-2 * x + 2, 3) / 2);
    }
}
