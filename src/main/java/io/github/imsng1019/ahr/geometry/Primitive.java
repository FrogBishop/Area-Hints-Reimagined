package io.github.imsng1019.ahr.geometry;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;

import java.math.BigInteger;
import java.util.*;

public interface Primitive {
    boolean contains(Point p);

    Bounds bounds();

    default boolean coversDomain() {
        return false;
    }

    static Primitive read(JsonElement entry, boolean editing) {
        var tagged = entry.getAsJsonObject();
        if (tagged.size() != 1) throw new IllegalArgumentException("Expected one primitive type");
        String type = tagged.keySet().iterator().next();
        var o = tagged.getAsJsonObject(type);
        return switch (type) {
            case "sphere", "cylinder" -> {
                boolean cylinder = type.equals("cylinder");
                var center =
                        cylinder ? Point.readXZ(o.get("center"), 0) : Point.read(o.get("center"));
                yield new Radial(
                        center,
                        checkedRadius(Json.integer(o.get("radiusSquared"))),
                        cylinder ? Bounds.Axis.read(o.get("y")) : Bounds.ANY,
                        cylinder);
            }
            case "prism" ->
                    new Mesh.Prism(
                            o.getAsJsonArray("vertices").asList().stream()
                                    .map(e -> Point.readXZ(e, 0))
                                    .toList(),
                            Bounds.Axis.read(o.get("y")),
                            Json.bool(o.get("insideAtInfinity")));
            case "polyhedron" -> new Mesh.Solid(o, editing);
            default -> throw new IllegalArgumentException("Unknown primitive " + type);
        };
    }

    static BigInteger checkedRadius(BigInteger r) {
        if (r.signum() < 0 || r.bitLength() > 64)
            throw new IllegalArgumentException(
                    "Squared radius must fit in an unsigned 64-bit integer");
        return r;
    }

    record Radial(Point center, BigInteger radiusSquared, Bounds.Axis height, boolean cylinder)
            implements Primitive {
        public boolean contains(Point p) {
            if (!height.contains(p.y())) return false;
            // Each individual square fits in an unsigned long. Subtract only after
            // comparing, so even opposite int32 corners never overflow a sum.
            long budget = radiusSquared.longValue();
            long dx = (long) p.x() - center.x(), dz = (long) p.z() - center.z();
            long xx = dx * dx, zz = dz * dz;
            if (Long.compareUnsigned(xx, budget) > 0) return false;
            budget -= xx;
            if (Long.compareUnsigned(zz, budget) > 0) return false;
            if (cylinder) return true;
            long dy = (long) p.y() - center.y();
            return Long.compareUnsigned(dy * dy, budget - zz) <= 0;
        }

        public Bounds bounds() {
            long r = radiusSquared.sqrt().longValueExact();
            return new Bounds(
                    Bounds.Axis.around(center.x(), r),
                    cylinder ? height : Bounds.Axis.around(center.y(), r),
                    Bounds.Axis.around(center.z(), r));
        }

        public boolean coversDomain() {
            if (!height.contains(Integer.MIN_VALUE) || !height.contains(Integer.MAX_VALUE))
                return false;
            return contains(
                    new Point(farthest(center.x()), farthest(center.y()), farthest(center.z())));
        }

        private static int farthest(int c) {
            return c < 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
    }
}
