package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.geometry.Bounds;

import net.minecraft.world.phys.Vec3;

/** Camera-clipped surface meshes. The grid step is shared across the whole primitive. */
final class CurvedSurfaces {
    private CurvedSurfaces() {}

    static void sphere(OutlineFrame frame, Vec3 center, double radius, int color) {
        Vec3 offset = frame.camera.subtract(center);
        double tolerance = Math.max(0.0625, Math.abs(offset.length() - radius) * 0.003);
        int cells = 2 * Math.max(3, (int) Math.ceil(0.5 * Math.sqrt(radius / tolerance)));
        double reach = frame.distance + tolerance;
        double[] camera = {offset.x, offset.y, offset.z};
        // Project the viewing cube onto each face of a cube enclosing the unit sphere.
        // Only those grid cells need vertices, even for the largest uint64 radius.
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double near = Math.max(radius / Math.sqrt(3), sign * camera[axis] - reach);
                double far = Math.min(radius, sign * camera[axis] + reach);
                if (near > far) continue;
                var u = gridRange(camera[(axis + 1) % 3], reach, near, far, cells);
                var v = gridRange(camera[(axis + 2) % 3], reach, near, far, cells);
                if (u.start >= u.end || v.start >= v.end) continue;
                Vec3[] previous = row(axis, sign, center, radius, cells, u, v.start);
                for (int j = v.start + 1; j <= v.end; j++) {
                    Vec3[] next = row(axis, sign, center, radius, cells, u, j);
                    for (int i = 1; i < next.length; i++) {
                        frame.face(color, previous[i - 1], previous[i], next[i], next[i - 1]);
                    }
                    previous = next;
                }
            }
        }
    }

    private record Range(int start, int end) {}

    private static Range gridRange(
            double center, double reach, double near, double far, int cells) {
        double low = center - reach, high = center + reach;
        double uMin = Math.clamp(Math.min(low / near, low / far), -1, 1);
        double uMax = Math.clamp(Math.max(high / near, high / far), -1, 1);
        return new Range(
                (int) Math.floor((uMin + 1) * cells / 2), (int) Math.ceil((uMax + 1) * cells / 2));
    }

    private static Vec3[] row(
            int axis, int sign, Vec3 center, double radius, int cells, Range uRange, int j) {
        double v = 2.0 * j / cells - 1;
        Vec3[] vertices = new Vec3[uRange.end - uRange.start + 1];
        for (int i = uRange.start; i <= uRange.end; i++) {
            double u = 2.0 * i / cells - 1;
            double w = radius / Math.sqrt(1 + u * u + v * v);
            vertices[i - uRange.start] =
                    switch (axis) {
                        case 0 -> center.add(sign * w, u * w, v * w);
                        case 1 -> center.add(v * w, sign * w, u * w);
                        default -> center.add(u * w, v * w, sign * w);
                    };
        }
        return vertices;
    }

    static void cylinder(
            OutlineFrame frame,
            Vec3 center,
            double radius,
            Bounds.Axis height,
            double low,
            double high,
            int color) {
        double dx = frame.camera.x - center.x, dz = frame.camera.z - center.z;
        double distance = Math.hypot(dx, dz), reach = Math.sqrt(2) * frame.distance;
        if (distance > radius + reach) return;
        boolean bottom = !height.unbounded() && low == height.low();
        boolean top = !height.unbounded() && high == height.high() && height.high() != height.low();
        double tolerance = Math.max(0.0625, Math.abs(distance - radius) * 0.003);
        if (distance + reach < radius - tolerance) {
            if (bottom) fullCap(frame, low, color);
            if (top) fullCap(frame, high, color);
            return;
        }
        double maxStep = 4 * Math.asin(Math.sqrt(Math.min(1, tolerance / (2 * radius))));
        int count = Math.max(24, (int) Math.ceil(2 * Math.PI / maxStep));
        double step = 2 * Math.PI / count;
        int start = 0, end = count;
        if (distance > reach) {
            double mid = Math.atan2(dz, dx), span = Math.asin(reach / distance);
            start = (int) Math.floor((mid - span) / step);
            end = (int) Math.ceil((mid + span) / step);
        }
        Vec3 bottomCenter = new Vec3(center.x, low, center.z);
        Vec3 topCenter = new Vec3(center.x, high, center.z);
        Vec3 a = ringPoint(bottomCenter, radius, start, count, step);
        for (int i = start + 1; i <= end; i++) {
            Vec3 b = ringPoint(bottomCenter, radius, i, count, step);
            Vec3 c = new Vec3(b.x, high, b.z), d = new Vec3(a.x, high, a.z);
            if (low < high) frame.face(color, a, b, c, d);
            if (bottom) frame.face(color, bottomCenter, b, a);
            if (top) frame.face(color, topCenter, d, c);
            a = b;
        }
    }

    private static Vec3 ringPoint(Vec3 c, double r, int index, int count, double step) {
        double angle = Math.floorMod(index, count) * step;
        return c.add(r * Math.cos(angle), 0, r * Math.sin(angle));
    }

    private static void fullCap(OutlineFrame frame, double y, int color) {
        double x = frame.camera.x, z = frame.camera.z, d = frame.distance;
        frame.face(
                color,
                new Vec3(x - d, y, z - d),
                new Vec3(x + d, y, z - d),
                new Vec3(x + d, y, z + d),
                new Vec3(x - d, y, z + d));
    }
}
