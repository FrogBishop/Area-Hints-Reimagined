package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.geometry.Point;

import net.minecraft.world.phys.Vec3;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.*;

/** A prism footprint as horizontal trapezoids, shared by both end faces. */
final class PrismCaps {
    private record Edge(double x0, double z0, double x1, double z1) {
        double x(double z) {
            return Math.fma((z - z0) / (z1 - z0), x1 - x0, x0);
        }
    }

    private record Strip(double z0, double z1, Edge left, Edge right) {}

    private final List<Strip> strips = new ArrayList<>();
    private final double minX, maxX;

    PrismCaps(List<Point> vertices, boolean insideAtInfinity) {
        var path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        if (!vertices.isEmpty()) {
            path.moveTo(vertices.getFirst().x(), vertices.getFirst().z());
            for (int i = 1; i < vertices.size(); i++)
                path.lineTo(vertices.get(i).x(), vertices.get(i).z());
            path.closePath();
        }
        // Area resolves self-crossings, holes and retraced spans. Between
        // successive vertex heights the remaining straight edges cannot cross.
        var area = new Area(path);
        var bounds = area.getBounds2D();
        minX = bounds.getMinX();
        maxX = bounds.getMaxX();
        var events = edgeEvents(area);
        double previous = Double.NEGATIVE_INFINITY;
        Set<Edge> active = new HashSet<>();
        for (var event : events.entrySet()) {
            double z = event.getKey();
            addStrips(previous, z, active, insideAtInfinity);
            for (var edge : event.getValue()) {
                if (!active.add(edge)) active.remove(edge);
            }
            previous = z;
        }
        if (insideAtInfinity) strips.add(new Strip(previous, Double.POSITIVE_INFINITY, null, null));
    }

    private static NavigableMap<Double, List<Edge>> edgeEvents(Area area) {
        var events = new TreeMap<Double, List<Edge>>();
        double firstX = 0, firstZ = 0, x = 0, z = 0;
        var coordinates = new double[6];
        for (var it = area.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(coordinates);
            double nextX = coordinates[0], nextZ = coordinates[1];
            if (type == PathIterator.SEG_MOVETO) {
                firstX = nextX;
                firstZ = nextZ;
            } else {
                if (type == PathIterator.SEG_CLOSE) {
                    nextX = firstX;
                    nextZ = firstZ;
                }
                if (z != nextZ) {
                    var edge = new Edge(x, z, nextX, nextZ);
                    events.computeIfAbsent(z, k -> new ArrayList<>()).add(edge);
                    events.computeIfAbsent(nextZ, k -> new ArrayList<>()).add(edge);
                }
            }
            x = nextX;
            z = nextZ;
        }
        return events;
    }

    private void addStrips(double z0, double z1, Set<Edge> active, boolean insideAtInfinity) {
        double mid = (z0 + z1) / 2;
        var edges = active.stream().sorted(Comparator.comparingDouble(e -> e.x(mid))).toList();
        boolean inside = insideAtInfinity;
        Edge left = null;
        for (var right : edges) {
            if (inside) strips.add(new Strip(z0, z1, left, right));
            inside = !inside;
            left = right;
        }
        if (inside) strips.add(new Strip(z0, z1, left, null));
    }

    void draw(OutlineFrame frame, double y, int color) {
        double leftLimit = Math.min(minX, frame.camera.x - frame.distance);
        double rightLimit = Math.max(maxX, frame.camera.x + frame.distance);
        for (var strip : strips) {
            double z0 = Math.max(strip.z0, frame.camera.z - frame.distance);
            double z1 = Math.min(strip.z1, frame.camera.z + frame.distance);
            if (z0 >= z1) continue;
            double a = strip.left == null ? leftLimit : strip.left.x(z0);
            double b = strip.right == null ? rightLimit : strip.right.x(z0);
            double c = strip.right == null ? rightLimit : strip.right.x(z1);
            double d = strip.left == null ? leftLimit : strip.left.x(z1);
            frame.face(
                    color,
                    new Vec3(a, y, z0),
                    new Vec3(b, y, z0),
                    new Vec3(c, y, z1),
                    new Vec3(d, y, z1));
        }
    }
}
