package io.github.imsng1019.ahr.geometry;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;

import java.util.*;

public record Bounds(Axis x, Axis y, Axis z) {
    public record Axis(int low, int high, boolean unbounded) {
        public Axis {
            if (low > high) throw new IllegalArgumentException("Reversed interval");
        }

        public Axis(int low, int high) {
            this(low, high, false);
        }

        public boolean contains(int v) {
            return unbounded || (low <= v && v <= high);
        }

        public Axis union(Axis a) {
            return unbounded || a.unbounded
                    ? ANY
                    : new Axis(Math.min(low, a.low), Math.max(high, a.high));
        }

        public Axis intersect(Axis a) {
            if (unbounded) return a;
            if (a.unbounded) return this;
            int l = Math.max(low, a.low), h = Math.min(high, a.high);
            return l <= h ? new Axis(l, h) : null;
        }

        public JsonElement json() {
            return unbounded ? new JsonPrimitive("unbounded") : Json.numbers(low, high);
        }

        public static Axis read(JsonElement e) {
            if (new JsonPrimitive("unbounded").equals(e)) return ANY;
            var a = Json.tuple(e, 2);
            return new Axis(Json.coord(a.get(0)), Json.coord(a.get(1)));
        }

        public static Axis around(int c, long r) {
            return new Axis(
                    (int) Math.max(Integer.MIN_VALUE, c - r),
                    (int) Math.min(Integer.MAX_VALUE, c + r));
        }
    }

    public static final Axis ANY = new Axis(Integer.MIN_VALUE, Integer.MAX_VALUE, true);
    public static final Bounds ALL = new Bounds(ANY, ANY, ANY);
    public static final Bounds EMPTY = new Bounds(null, null, null);

    public boolean empty() {
        return x == null;
    }

    public boolean contains(Point p) {
        return !empty() && x.contains(p.x()) && y.contains(p.y()) && z.contains(p.z());
    }

    public Bounds union(Bounds b) {
        if (empty()) return b;
        if (b.empty()) return this;
        return new Bounds(x.union(b.x), y.union(b.y), z.union(b.z));
    }

    public Bounds intersect(Bounds b) {
        if (empty() || b.empty()) return EMPTY;
        var xx = x.intersect(b.x);
        var yy = y.intersect(b.y);
        var zz = z.intersect(b.z);
        return xx == null || yy == null || zz == null ? EMPTY : new Bounds(xx, yy, zz);
    }

    public static Bounds enclosing(Collection<Point> points) {
        Bounds box = EMPTY;
        for (Point p : points)
            box =
                    box.union(
                            new Bounds(
                                    new Axis(p.x(), p.x()),
                                    new Axis(p.y(), p.y()),
                                    new Axis(p.z(), p.z())));
        return box;
    }

    public JsonElement json() {
        if (empty()) return new JsonPrimitive("empty");
        var o = new JsonObject();
        o.add("x", x.json());
        o.add("y", y.json());
        o.add("z", z.json());
        return o;
    }

    public static Bounds read(JsonElement e) {
        if (new JsonPrimitive("empty").equals(e)) return EMPTY;
        var o = e.getAsJsonObject();
        return new Bounds(Axis.read(o.get("x")), Axis.read(o.get("y")), Axis.read(o.get("z")));
    }
}
