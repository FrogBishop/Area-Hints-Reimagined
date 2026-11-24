package io.github.imsng1019.ahr.client;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;
import io.github.imsng1019.ahr.geometry.*;

import java.math.BigInteger;
import java.util.*;

final class PrimitiveDraft {
    final String type;
    final List<Point> points = new ArrayList<>();
    final List<Point> faces = new ArrayList<>();
    String height = "\"unbounded\"";
    boolean infinity;
    private BigInteger savedRadius;

    PrimitiveDraft(String type, JsonObject saved, int playerY) {
        this.type = type;
        if (saved == null) return;
        int y = playerY;
        if (projected()) {
            height = saved.get("y").toString();
            var range = Bounds.Axis.read(saved.get("y"));
            if (!range.unbounded()) y = range.low();
        }
        if (radial()) {
            savedRadius = Json.integer(saved.get("radiusSquared"));
            points.add(
                    projected()
                            ? Point.readXZ(saved.get("center"), y)
                            : Point.read(saved.get("center")));
        } else {
            infinity = Json.bool(saved.get("insideAtInfinity"));
            for (var v : saved.getAsJsonArray("vertices"))
                points.add(projected() ? Point.readXZ(v, y) : Point.read(v));
            if (type.equals("polyhedron"))
                for (var face : saved.getAsJsonArray("faces")) faces.add(Point.read(face));
        }
    }

    boolean projected() {
        return type.equals("cylinder") || type.equals("prism");
    }

    boolean radial() {
        return type.equals("sphere") || type.equals("cylinder");
    }

    boolean mark(Point p) {
        if (radial() && points.size() >= 2) return false;
        points.add(p);
        return true;
    }

    boolean[] used() {
        boolean[] used = new boolean[points.size()];
        for (var f : faces) {
            for (int i : new int[] {f.x(), f.y(), f.z()}) {
                if (i < 0 || i >= used.length)
                    throw new IllegalArgumentException("Unknown vertex " + i);
                used[i] = true;
            }
        }
        return used;
    }

    boolean unused() {
        if (!type.equals("polyhedron")) return false;
        for (boolean v : used()) if (!v) return true;
        return false;
    }

    void deletePoint(int index) {
        if (used()[index])
            throw new IllegalArgumentException("Remove the faces using this vertex first");
        points.remove(index);
        faces.replaceAll(
                f -> new Point(after(f.x(), index), after(f.y(), index), after(f.z(), index)));
    }

    private static int after(int value, int removed) {
        return value > removed ? value - 1 : value;
    }

    JsonObject save(boolean removeUnused) {
        var data = new JsonObject();
        if (projected()) data.add("y", Bounds.Axis.read(Json.parse(height)).json());
        if (radial()) {
            if (points.isEmpty() || (points.size() == 1 && savedRadius == null))
                throw new IllegalArgumentException("Mark the center and a surface point");
            var center = points.getFirst();
            var radius =
                    points.size() == 2
                            ? center.distanceSquared(points.get(1), projected())
                            : savedRadius;
            data.add("center", projected() ? center.xz() : center.json());
            data.addProperty("radiusSquared", Primitive.checkedRadius(radius));
        } else {
            data.addProperty("insideAtInfinity", infinity);
            var vertices = new JsonArray();
            boolean[] used = used();
            int[] remap = new int[points.size()];
            for (int i = 0; i < points.size(); i++) {
                if (removeUnused && !used[i]) continue;
                remap[i] = vertices.size();
                vertices.add(projected() ? points.get(i).xz() : points.get(i).json());
            }
            data.add("vertices", vertices);
            if (type.equals("polyhedron")) {
                var triangles = new JsonArray();
                for (var f : faces)
                    triangles.add(Json.numbers(remap[f.x()], remap[f.y()], remap[f.z()]));
                data.add("faces", triangles);
            }
        }
        var tagged = new JsonObject();
        tagged.add(type, data);
        Primitive.read(tagged, true);
        return tagged;
    }
}
