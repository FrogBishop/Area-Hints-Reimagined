package io.github.imsng1019.ahr.client;

import com.google.gson.JsonObject;

import io.github.imsng1019.ahr.data.Json;
import io.github.imsng1019.ahr.geometry.Bounds;
import io.github.imsng1019.ahr.geometry.Point;

import net.minecraft.world.phys.Vec3;

import java.math.BigInteger;
import java.util.*;

/** Display geometry of one primitive, independent of its region expression and membership AABB. */
interface PrimitiveOutline {
    void draw(OutlineFrame frame, int color);

    static PrimitiveOutline read(JsonObject tagged) {
        String type = tagged.keySet().iterator().next();
        return from(new PrimitiveDraft(type, tagged.getAsJsonObject(type), 0));
    }

    static PrimitiveOutline from(PrimitiveDraft draft) {
        Bounds.Axis height = null;
        if (draft.projected()) {
            try {
                height = Bounds.Axis.read(Json.parse(draft.height));
            } catch (RuntimeException invalidHeightWhileTyping) {
                return null;
            }
        }
        if (draft.radial()) {
            if (draft.points.isEmpty()) return null;
            BigInteger squared = draft.radiusSquared();
            return new Radial(
                    draft.points.getFirst(),
                    height,
                    squared == null ? null : Math.sqrt(squared.doubleValue()));
        }
        return draft.projected()
                ? new Prism(draft.points, height, draft.infinity)
                : new Polyhedron(draft.points, draft.faces);
    }

    record Radial(Point center, Bounds.Axis height, Double radius) implements PrimitiveOutline {
        @Override
        public void draw(OutlineFrame frame, int color) {
            if (radius == null) return;
            if (height != null) {
                cylinder(frame, color);
                return;
            }
            if (radius == 0) {
                frame.mark(center, 0.18, color);
                return;
            }
            Vec3 c = xyz(center);
            if (Math.abs(frame.camera.distanceTo(c) - radius) > Math.sqrt(3) * frame.distance)
                return;
            CurvedSurfaces.sphere(frame, c, radius, color);
            for (var plane : OutlineFrame.Plane.values()) frame.circle(c, radius, plane, color);
        }

        private void cylinder(OutlineFrame frame, int color) {
            double lo = Math.max(frame.camera.y - frame.distance, height.low());
            double hi = Math.min(frame.camera.y + frame.distance, height.high());
            if (lo > hi) return;
            if (radius == 0) {
                if (height.low() == height.high())
                    frame.mark(new Point(center.x(), height.low(), center.z()), .18, color);
                else post(frame, center.x(), center.z(), lo, hi, color);
                return;
            }
            CurvedSurfaces.cylinder(frame, xyz(center), radius, height, lo, hi, color);
            double dx = frame.camera.x - center.x(), dz = frame.camera.z - center.z();
            if (Math.abs(Math.hypot(dx, dz) - radius) > Math.sqrt(2) * frame.distance) return;
            for (double y : ringHeights(frame, height))
                frame.circle(
                        new Vec3(center.x(), y, center.z()), radius, OutlineFrame.Plane.XZ, color);
            if (lo == hi) return;
            for (int i = 0; i < 8; i++) side(frame, i * Math.PI / 4, lo, hi, color);
            if (radius > frame.distance) side(frame, Math.atan2(dz, dx), lo, hi, color);
        }

        private void side(OutlineFrame frame, double angle, double lo, double hi, int color) {
            post(
                    frame,
                    center.x() + radius * Math.cos(angle),
                    center.z() + radius * Math.sin(angle),
                    lo,
                    hi,
                    color);
        }
    }

    static Vec3 xyz(Point p) {
        return new Vec3(p.x(), p.y(), p.z());
    }

    private static void post(OutlineFrame f, double x, double z, double lo, double hi, int color) {
        f.line(new Vec3(x, lo, z), new Vec3(x, hi, z), color);
    }

    // When both ends are outside the view, draw a section through the camera.
    // The sides continue through this section. It is not an end cap.
    static double[] ringHeights(OutlineFrame f, Bounds.Axis h) {
        double low = f.camera.y - f.distance, high = f.camera.y + f.distance;
        if (h.high() < low || h.low() > high) return new double[0];
        boolean bottom = !h.unbounded() && h.low() >= low && h.low() <= high;
        boolean top = !h.unbounded() && h.high() >= low && h.high() <= high;
        if (bottom && top && h.low() != h.high()) return new double[] {h.low(), h.high()};
        if (bottom || top) return new double[] {bottom ? h.low() : h.high()};
        return new double[] {f.camera.y};
    }

    record Edge(Point a, Point b) {
        void atHeight(OutlineFrame f, double y, int color) {
            f.line(new Vec3(a.x(), y, a.z()), new Vec3(b.x(), y, b.z()), color);
        }
    }

    final class Prism implements PrimitiveOutline {
        final Bounds.Axis height;
        final Bounds bounds;
        private final PrismCaps caps;
        private final boolean insideAtInfinity;
        final List<Edge> rim = new ArrayList<>();
        final Set<Point> corners = new HashSet<>();

        private record Support(long dx, long dz, BigInteger offset) {}

        Prism(List<Point> points, Bounds.Axis height, boolean insideAtInfinity) {
            this.height = height;
            this.insideAtInfinity = insideAtInfinity;
            caps = height.unbounded() ? null : new PrismCaps(points, insideAtInfinity);
            var flat = points.stream().map(p -> new Point(p.x(), 0, p.z())).toList();
            var box = Bounds.enclosing(flat);
            bounds = box.empty() ? Bounds.EMPTY : new Bounds(box.x(), height, box.z());
            // XOR intervals on each supporting line. A bridge cancels even
            // when its outward and return trips use different subdivisions.
            var events = new HashMap<Support, TreeSet<Point>>();
            for (int i = 0; i < flat.size(); i++) {
                Point a = flat.get(i), b = flat.get((i + 1) % flat.size());
                long dx = (long) b.x() - a.x(), dz = (long) b.z() - a.z();
                if (dx == 0 && dz == 0) continue;
                long g = BigInteger.valueOf(dx).gcd(BigInteger.valueOf(dz)).longValueExact();
                if (dx < 0 || (dx == 0 && dz < 0)) g = -g;
                dx /= g;
                dz /= g;
                var offset =
                        BigInteger.valueOf(dx)
                                .multiply(BigInteger.valueOf(a.z()))
                                .subtract(
                                        BigInteger.valueOf(dz).multiply(BigInteger.valueOf(a.x())));
                var ends =
                        events.computeIfAbsent(
                                new Support(dx, dz, offset),
                                k ->
                                        new TreeSet<>(
                                                Comparator.comparingInt(Point::x)
                                                        .thenComparingInt(Point::z)));
                if (!ends.add(a)) ends.remove(a);
                if (!ends.add(b)) ends.remove(b);
            }
            for (var ends : events.values()) {
                var it = ends.iterator();
                while (it.hasNext()) {
                    Point a = it.next(), b = it.next();
                    rim.add(new Edge(a, b));
                    corners.add(a);
                    corners.add(b);
                }
            }
        }

        @Override
        public void draw(OutlineFrame f, int color) {
            double lo = Math.max(f.camera.y - f.distance, height.low()),
                    hi = Math.min(f.camera.y + f.distance, height.high());
            if (lo > hi) return;
            boolean rimVisible = f.visible(bounds);
            if (caps != null && (insideAtInfinity || rimVisible)) {
                if (lo == height.low()) caps.draw(f, lo, color);
                if (hi == height.high() && height.low() != height.high()) caps.draw(f, hi, color);
            }
            if (!rimVisible) return;
            for (double y : ringHeights(f, height)) for (var e : rim) e.atHeight(f, y, color);
            if (lo < hi) {
                for (var p : corners) post(f, p.x(), p.z(), lo, hi, color);
                for (var e : rim)
                    f.face(
                            color,
                            new Vec3(e.a.x(), lo, e.a.z()),
                            new Vec3(e.b.x(), lo, e.b.z()),
                            new Vec3(e.b.x(), hi, e.b.z()),
                            new Vec3(e.a.x(), hi, e.a.z()));
            }
        }
    }

    final class Polyhedron implements PrimitiveOutline {
        final List<Edge> edges = new ArrayList<>();
        final Bounds bounds;
        private final List<Point> vertices;
        private final List<Point> triangles;

        Polyhedron(List<Point> vertices, List<Point> faces) {
            this.vertices = List.copyOf(vertices);
            this.triangles = List.copyOf(faces);
            // A stored vertex with no incident face contributes no edge.
            Set<Long> seen = new HashSet<>();
            for (var face : faces) {
                add(vertices, seen, face.x(), face.y());
                add(vertices, seen, face.y(), face.z());
                add(vertices, seen, face.z(), face.x());
            }
            bounds = Bounds.enclosing(vertices);
        }

        private void add(List<Point> vertices, Set<Long> seen, int i, int j) {
            long key = ((long) Math.min(i, j) << 32) | Integer.toUnsignedLong(Math.max(i, j));
            if (seen.add(key)) edges.add(new Edge(vertices.get(i), vertices.get(j)));
        }

        @Override
        public void draw(OutlineFrame f, int color) {
            if (!f.visible(bounds)) return;
            for (var e : edges) f.line(xyz(e.a), xyz(e.b), color);
            for (var tri : triangles)
                f.face(
                        color,
                        xyz(vertices.get(tri.x())),
                        xyz(vertices.get(tri.y())),
                        xyz(vertices.get(tri.z())));
        }
    }
}
