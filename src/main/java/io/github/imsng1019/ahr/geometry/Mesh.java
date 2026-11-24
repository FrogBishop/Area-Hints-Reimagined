package io.github.imsng1019.ahr.geometry;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;

import java.math.BigInteger;
import java.util.*;

final class Mesh {
    private Mesh() {}

    private record Pair(int u, int v) {
        static Pair project(Point p, int axis) {
            return switch (axis) {
                case 0 -> new Pair(p.y(), p.z());
                case 1 -> new Pair(p.z(), p.x());
                default -> new Pair(p.x(), p.y());
            };
        }
    }

    private static BigInteger product(long a, long b) {
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
    }

    private static BigInteger orient(Pair a, Pair b, Pair p) {
        return product((long) b.u - a.u, (long) p.v - a.v)
                .subtract(product((long) b.v - a.v, (long) p.u - a.u));
    }

    // Sign at (u + epsilon, v + epsilon^2). The first nonzero coefficient
    // assigns a projected shared edge to exactly one adjacent triangle.
    private static int side(Pair a, Pair b, Pair p, boolean perturb) {
        int sign = orient(a, b, p).signum();
        if (sign != 0 || !perturb) return sign;
        int first = Integer.compare(a.v, b.v);
        return first != 0 ? first : Integer.compare(b.u, a.u);
    }

    private record Direction(long u, long v) {}

    private static final class Rim {
        final Set<Direction> rays = new HashSet<>();

        void endpoint(Pair p, Pair a) {
            long u = (long) a.u - p.u, v = (long) a.v - p.v;
            long g = gcd(Math.abs(u), Math.abs(v));
            if (g == 0) return;
            var dir = new Direction(u / g, v / g);
            if (!rays.add(dir)) rays.remove(dir);
        }

        void edge(Pair a, Pair b, Pair p) {
            if (p.u < Math.min(a.u, b.u)
                    || p.u > Math.max(a.u, b.u)
                    || p.v < Math.min(a.v, b.v)
                    || p.v > Math.max(a.v, b.v)) return;
            endpoint(p, a);
            endpoint(p, b);
        }

        static long gcd(long a, long b) {
            while (b != 0) {
                long r = a % b;
                a = b;
                b = r;
            }
            return a;
        }
    }

    static final class Prism implements Primitive {
        final List<Pair> walk;
        final Bounds.Axis height;
        final boolean infinity;
        final Bounds box;

        Prism(List<Point> vertices, Bounds.Axis height, boolean infinity) {
            walk = vertices.stream().map(p -> new Pair(p.x(), p.z())).toList();
            this.height = height;
            this.infinity = infinity;
            var outline = Bounds.enclosing(vertices);
            box =
                    infinity
                            ? new Bounds(Bounds.ANY, height, Bounds.ANY)
                            : outline.empty()
                                    ? Bounds.EMPTY
                                    : new Bounds(outline.x(), height, outline.z());
        }

        public Bounds bounds() {
            return box;
        }

        public boolean contains(Point point) {
            if (!height.contains(point.y())) return false;
            Pair p = new Pair(point.x(), point.z());
            boolean in = infinity;
            Rim rim = new Rim();
            for (int i = 0; i < walk.size(); i++) {
                Pair a = walk.get(i), b = walk.get((i + 1) % walk.size());
                int s = orient(a, b, p).signum();
                if (s == 0) rim.edge(a, b, p);
                if ((a.v > p.v) != (b.v > p.v) && s == Integer.compare(b.v, a.v)) in = !in;
            }
            return in || !rim.rays.isEmpty();
        }
    }

    private record Normal(BigInteger x, BigInteger y, BigInteger z) {
        static Normal of(Point a, Point b, Point c) {
            var n = new BigInteger[3];
            for (int axis = 0; axis < 3; axis++)
                n[axis] =
                        orient(Pair.project(a, axis), Pair.project(b, axis), Pair.project(c, axis));
            BigInteger g = n[0].gcd(n[1]).gcd(n[2]);
            if (g.signum() == 0) throw new IllegalArgumentException("Triangle has zero area");
            int first =
                    n[0].signum() != 0
                            ? n[0].signum()
                            : n[1].signum() != 0 ? n[1].signum() : n[2].signum();
            if (first < 0) g = g.negate();
            return new Normal(n[0].divide(g), n[1].divide(g), n[2].divide(g));
        }

        int axis() {
            return x.signum() != 0 ? 0 : y.signum() != 0 ? 1 : 2;
        }

        int offset(Point p, Point a) {
            return x.multiply(BigInteger.valueOf((long) p.x() - a.x()))
                    .add(y.multiply(BigInteger.valueOf((long) p.y() - a.y())))
                    .add(z.multiply(BigInteger.valueOf((long) p.z() - a.z())))
                    .signum();
        }
    }

    private record Triangle(Point anchor, Normal normal, Pair a, Pair b, Pair c, int orientation) {
        static Triangle of(Point a, Point b, Point c) {
            var n = Normal.of(a, b, c);
            int axis = n.axis();
            var u = Pair.project(a, axis);
            var v = Pair.project(b, axis);
            var w = Pair.project(c, axis);
            return new Triangle(a, n, u, v, w, orient(u, v, w).signum());
        }

        boolean inside(Pair p, boolean perturb) {
            return side(a, b, p, perturb) * orientation >= 0
                    && side(b, c, p, perturb) * orientation >= 0
                    && side(c, a, p, perturb) * orientation >= 0;
        }

        void incidentRays(Pair p, Rim rim) {
            if (side(a, b, p, false) == 0) rim.edge(a, b, p);
            if (side(b, c, p, false) == 0) rim.edge(b, c, p);
            if (side(c, a, p, false) == 0) rim.edge(c, a, p);
        }
    }

    private static final class Patch {
        final Rim rim = new Rim();
        boolean covered;
    }

    static final class Solid implements Primitive {
        final List<Triangle> triangles = new ArrayList<>();
        final boolean infinity;
        final Bounds box;

        Solid(JsonObject o, boolean editing) {
            infinity = Json.bool(o.get("insideAtInfinity"));
            var vertices = o.getAsJsonArray("vertices").asList().stream().map(Point::read).toList();
            var oddEdges = new HashSet<Long>();
            Bounds extent = Bounds.EMPTY;
            for (var face : o.getAsJsonArray("faces")) {
                var f = Json.tuple(face, 3);
                int i = Json.coord(f.get(0)), j = Json.coord(f.get(1)), k = Json.coord(f.get(2));
                var a = vertices.get(i);
                var b = vertices.get(j);
                var c = vertices.get(k);
                triangles.add(Triangle.of(a, b, c));
                extent = extent.union(Bounds.enclosing(List.of(a, b, c)));
                if (editing) {
                    toggle(oddEdges, i, j);
                    toggle(oddEdges, j, k);
                    toggle(oddEdges, k, i);
                }
            }
            if (!oddEdges.isEmpty())
                throw new IllegalArgumentException(
                        "A polyhedron edge occurs an odd number of times");
            box = infinity ? Bounds.ALL : extent;
        }

        private static void toggle(Set<Long> odd, int a, int b) {
            long key = ((long) Math.min(a, b) << 32) | Integer.toUnsignedLong(Math.max(a, b));
            if (!odd.add(key)) odd.remove(key);
        }

        public Bounds bounds() {
            return box;
        }

        public boolean contains(Point point) {
            boolean in = infinity;
            Map<Normal, Patch> incident = null;
            for (var t : triangles) {
                int offset = t.normal.offset(point, t.anchor);
                // The canonical normal has positive X on faces transverse to the +X ray.
                if (offset > 0 || (offset != 0 && t.normal.x.signum() == 0)) continue;
                Pair p = Pair.project(point, t.normal.axis());
                if (offset < 0) {
                    if (t.inside(p, true)) in = !in;
                } else if (t.inside(p, false)) {
                    if (incident == null) incident = new HashMap<>();
                    var patch = incident.computeIfAbsent(t.normal, k -> new Patch());
                    patch.covered ^= t.inside(p, true);
                    t.incidentRays(p, patch.rim);
                }
            }
            if (incident != null) {
                for (var patch : incident.values())
                    if (patch.covered || !patch.rim.rays.isEmpty()) return true;
            }
            return in;
        }
    }
}
