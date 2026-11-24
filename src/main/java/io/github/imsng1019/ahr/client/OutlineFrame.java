package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.geometry.Bounds;
import io.github.imsng1019.ahr.geometry.Point;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Collect clipped world-space geometry before passing it to the render thread. */
final class OutlineFrame {
    record Line(Vec3 from, Vec3 to, int color) {}

    record Face(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {}

    record Geometry(List<Face> faces, List<Line> lines) {}

    enum Plane {
        XZ,
        XY,
        YZ;

        Vec3 at(Vec3 c, double u, double v) {
            return switch (this) {
                case XZ -> c.add(u, 0, v);
                case XY -> c.add(u, v, 0);
                case YZ -> c.add(0, u, v);
            };
        }
    }

    final Vec3 camera;
    final double distance;
    final List<Line> lines = new ArrayList<>();
    final List<Face> faces = new ArrayList<>();

    OutlineFrame(Vec3 camera, double distance) {
        this.camera = camera;
        this.distance = distance;
    }

    Geometry snapshot() {
        return new Geometry(List.copyOf(faces), List.copyOf(lines));
    }

    // The input is one triangle or quad. Clip before the renderer converts the
    // camera-relative coordinates to floats, even if every vertex is offscreen.
    void face(int color, Vec3... vertices) {
        int outsideAny = 0, outsideAll = 63;
        for (Vec3 p : vertices) {
            int code = 0;
            if (p.x < camera.x - distance) code |= 1;
            if (p.x > camera.x + distance) code |= 2;
            if (p.y < camera.y - distance) code |= 4;
            if (p.y > camera.y + distance) code |= 8;
            if (p.z < camera.z - distance) code |= 16;
            if (p.z > camera.z + distance) code |= 32;
            outsideAny |= code;
            outsideAll &= code;
        }
        if (outsideAll != 0) return;
        int tint = (color & 0x00ffffff) | 0x38000000;
        if (outsideAny == 0) {
            faces.add(
                    new Face(
                            vertices[0],
                            vertices[1],
                            vertices[2],
                            vertices[vertices.length - 1],
                            tint));
            return;
        }
        if (vertices.length == 4) {
            // Preserve the same diagonal as an unclipped GPU quad on curved surfaces.
            face(color, vertices[0], vertices[1], vertices[2]);
            face(color, vertices[0], vertices[2], vertices[3]);
            return;
        }
        List<Vec3> polygon = List.of(vertices);
        for (int side = 0; side < 6; side++) {
            if ((outsideAny & (1 << side)) == 0) continue;
            Direction.Axis axis =
                    switch (side / 2) {
                        case 0 -> Direction.Axis.X;
                        case 1 -> Direction.Axis.Y;
                        default -> Direction.Axis.Z;
                    };
            boolean lower = (side & 1) == 0;
            double limit = camera.get(axis) + (lower ? -distance : distance);
            var clipped = new ArrayList<Vec3>(polygon.size() + 1);
            Vec3 prev = polygon.getLast();
            for (Vec3 p : polygon) {
                double a = prev.get(axis) - limit, b = p.get(axis) - limit;
                if ((a < 0 && b > 0) || (a > 0 && b < 0)) clipped.add(prev.lerp(p, a / (a - b)));
                if (lower ? b >= 0 : b <= 0) clipped.add(p);
                prev = p;
            }
            if (clipped.size() < 3) return;
            polygon = clipped;
        }
        for (int i = 1; i + 1 < polygon.size(); i++)
            faces.add(
                    new Face(
                            polygon.getFirst(),
                            polygon.get(i),
                            polygon.get(i + 1),
                            polygon.get(i + 1),
                            tint));
    }

    boolean visible(Bounds bounds) {
        return !bounds.empty()
                && near(bounds.x(), camera.x)
                && near(bounds.y(), camera.y)
                && near(bounds.z(), camera.z);
    }

    private boolean near(Bounds.Axis axis, double coordinate) {
        return axis.unbounded()
                || (axis.high() >= coordinate - distance && axis.low() <= coordinate + distance);
    }

    void mark(Point p, double size, int color) {
        line(new Vec3(p.x() - size, p.y(), p.z()), new Vec3(p.x() + size, p.y(), p.z()), color);
        line(new Vec3(p.x(), p.y() - size, p.z()), new Vec3(p.x(), p.y() + size, p.z()), color);
        line(new Vec3(p.x(), p.y(), p.z() - size), new Vec3(p.x(), p.y(), p.z() + size), color);
    }

    void line(Vec3 a, Vec3 b, int color) {
        // Clip in double precision. A visible edge can have both endpoints outside the view.
        double first = 0, last = 1;
        for (int axis = 0; axis < 3; axis++) {
            double start =
                    switch (axis) {
                        case 0 -> a.x - camera.x;
                        case 1 -> a.y - camera.y;
                        default -> a.z - camera.z;
                    };
            double end =
                    switch (axis) {
                        case 0 -> b.x - camera.x;
                        case 1 -> b.y - camera.y;
                        default -> b.z - camera.z;
                    };
            if (Math.min(start, end) > distance || Math.max(start, end) < -distance) return;
            if (start == end) continue;
            double t0 = (-distance - start) / (end - start),
                    t1 = (distance - start) / (end - start);
            first = Math.max(first, Math.min(t0, t1));
            last = Math.min(last, Math.max(t0, t1));
            if (first >= last) return;
        }
        if (!a.equals(b))
            lines.add(
                    new Line(
                            first == 0 ? a : a.lerp(b, first),
                            last == 1 ? b : a.lerp(b, last),
                            color));
    }

    void circle(Vec3 center, double radius, Plane plane, int color) {
        if (radius <= 0) return;
        double dx = camera.x - center.x, dy = camera.y - center.y, dz = camera.z - center.z;
        double u = plane == Plane.YZ ? dy : dx;
        double v = plane == Plane.XY ? dy : dz;
        double normal =
                switch (plane) {
                    case XZ -> dy;
                    case XY -> dz;
                    case YZ -> dx;
                };
        if (Math.abs(normal) > distance) return;
        double d = Math.hypot(u, v), reach = Math.sqrt(2) * distance;
        if (Math.abs(d - radius) > reach) return;
        double mid = 0, halfArc = Math.PI;
        if (d + radius > reach && d > 0) {
            mid = Math.atan2(v, u);
            // Half-angle form stays accurate near a circle billions of blocks in radius.
            double delta = d - radius;
            double sinSquared = (reach - delta) * (reach + delta) / (4 * d * radius);
            halfArc = 2 * Math.asin(Math.sqrt(Math.clamp(sinSquared, 0, 1)));
        }
        double step =
                Math.min(
                        Math.PI / 12, 4 * Math.asin(Math.sqrt(Math.min(1, 0.0625 / (2 * radius)))));
        int segments = Math.max(1, (int) Math.ceil(2 * halfArc / step));
        double start = mid - halfArc;
        Vec3 prev = plane.at(center, radius * Math.cos(start), radius * Math.sin(start));
        for (int i = 1; i <= segments; i++) {
            double theta = start + 2 * halfArc * i / segments;
            Vec3 next = plane.at(center, radius * Math.cos(theta), radius * Math.sin(theta));
            line(prev, next, color);
            prev = next;
        }
    }
}
