package io.github.imsng1019.ahr.geometry;

import static org.junit.Assert.*;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;

import org.junit.Test;

import java.math.BigInteger;
import java.util.*;

public class GeometryTest {
    private static Primitive primitive(String text) {
        return Primitive.read(Json.parse(text), true);
    }

    private static final String MAX = "18446744073709551615";

    private static String sphere(Point p, String r) {
        return "{\"sphere\":{\"center\":" + p.json() + ",\"radiusSquared\":" + r + "}}";
    }

    static void hit(Primitive p, boolean expected, int x, int y, int z) {
        var q = new Point(x, y, z);
        assertEquals(q.toString(), expected, p.contains(q));
        if (expected) assertTrue("AABB rejected " + q, p.bounds().contains(q));
    }

    @Test
    public void exactUnsignedRadiusAndFlooredCoordinates() {
        assertEquals(new Point(-1, 10, -3), Point.floor(-0.01, 10.99, -2.1));
        int low = Integer.MIN_VALUE, high = Integer.MAX_VALUE;
        var s = primitive(sphere(new Point(low, low, low), MAX));
        hit(s, true, high, low, low);
        hit(s, false, high, high, high);
        assertFalse(s.coversDomain());
        assertTrue(primitive(sphere(new Point(0, 0, 0), MAX)).coversDomain());
        assertThrows(
                IllegalArgumentException.class,
                () -> primitive(sphere(new Point(0, 0, 0), "18446744073709551616")));
        for (int bits = 0; bits < 64; bits++) {
            BigInteger r = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
            var part = primitive(sphere(new Point(1, 2, 3), r.toString()));
            long extent = r.sqrt().longValueExact();
            assertEquals(Math.min(high, 1 + extent), part.bounds().x().high());
        }
        var c =
                primitive(
                        "{\"cylinder\":{\"center\":[0,0],\"radiusSquared\":3,\"y\":\"unbounded\"}}");
        hit(c, true, 1, low, 1);
        hit(c, false, 2, high, 0);
    }

    private static Primitive prism(String walk, boolean far) {
        return primitive(
                "{\"prism\":{\"vertices\":"
                        + walk
                        + ",\"insideAtInfinity\":"
                        + far
                        + ",\"y\":[60,70]}}");
    }

    @Test
    public void holesRetracedBridgesAndSelfIntersections() {
        for (boolean far : new boolean[] {true, false}) {
            var hole = prism("[[0,0],[0,12],[12,0],[8,2],[4,6],[4,2],[8,2],[12,0]]", far);
            hit(hole, far, 5, 65, 3);
            hit(hole, true, 5, 65, 2);
            hit(hole, !far, 10, 65, 1);
            hit(hole, false, 5, 71, 2);
            var bow = prism("[[0,0],[10,10],[10,0],[0,10]]", far);
            hit(bow, !far, 2, 65, 4);
            hit(bow, !far, 8, 65, 4);
            hit(bow, far, -1, 65, 4);
            hit(bow, true, 5, 65, 5);
            var spur = prism("[[0,0],[0,8],[8,0],[12,0],[10,0],[8,0]]", far);
            hit(spur, far, 10, 65, 0);
        }
    }

    @Test
    public void unsignedDistanceMatchesArbitraryPrecisionAcrossInt32Space() {
        var random = new Random(263);
        for (int i = 0; i < 4096; i++) {
            var center = new Point(random.nextInt(), random.nextInt(), random.nextInt());
            var query = new Point(random.nextInt(), random.nextInt(), random.nextInt());
            boolean horizontal = random.nextBoolean();
            BigInteger r = new BigInteger(64, random);
            var part = new Primitive.Radial(center, r, Bounds.ANY, horizontal);
            assertEquals(
                    center.distanceSquared(query, horizontal).compareTo(r) <= 0,
                    part.contains(query));
            if (part.contains(query)) assertTrue(part.bounds().contains(query));
        }
    }

    private static final String TETRA =
            "{\"polyhedron\":{\"vertices\":[[0,0,0],[4,0,0],[0,4,0],[0,0,4],[999,999,999]],"
                    + "\"faces\":[[2,1,0],[3,0,1],[0,2,3],[3,2,1]],\"insideAtInfinity\":false}}";

    @Test
    public void tetrahedronIncludesEveryFaceEdgeAndVertexRegardlessOfWinding() {
        for (boolean far : new boolean[] {false, true}) {
            var p = primitive(TETRA.replace("false", String.valueOf(far)));
            for (int x = -1; x <= 5; x++)
                for (int y = -1; y <= 5; y++)
                    for (int z = -1; z <= 5; z++) {
                        boolean closed = x >= 0 && y >= 0 && z >= 0 && x + y + z <= 4;
                        boolean open = x > 0 && y > 0 && z > 0 && x + y + z < 4;
                        hit(p, far ? !open : closed, x, y, z);
                    }
            if (!far) assertEquals(4, p.bounds().x().high());
        }
        var large =
                primitive(
                        TETRA.replace("[4,0,0]", "[2147483647,0,0]")
                                .replace("[0,4,0]", "[0,2147483647,0]")
                                .replace("[0,0,4]", "[0,0,2147483647]")
                                .replace("[0,0,0]", "[-2147483648,-2147483648,-2147483648]"));
        hit(large, true, 0, 0, 0);
        hit(large, false, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    public void evenEdgeMultiplicityAndCoplanarPatches() {
        var json = Json.parse(TETRA).getAsJsonObject();
        var f = json.getAsJsonObject("polyhedron").getAsJsonArray("faces");
        f.addAll(f.deepCopy());
        var duplicate = Primitive.read(json, true);
        hit(duplicate, false, 0, 1, 1);
        hit(duplicate, false, 1, 1, 1);
        f.remove(0);
        assertThrows(IllegalArgumentException.class, () -> Primitive.read(json, true));
    }

    @Test
    public void indexedExpressionBoundsAndNegatedBoundaries() {
        var parts = new JsonArray();
        parts.add(Json.parse(sphere(new Point(0, 0, 0), "25")));
        parts.add(Json.parse(sphere(new Point(0, 0, 0), "4")));
        for (int i = 2; i <= 10; i++) parts.add(Json.parse(sphere(new Point(200, 0, 0), "0")));
        var obj = new JsonObject();
        obj.add("primitives", parts);
        obj.addProperty("expression", " 0 & !1 | ( 10 & !10 ) ");
        var p = new RegionPredicate(obj, true);
        assertFalse(p.contains(new Point(2, 0, 0)));
        assertTrue(p.contains(new Point(5, 0, 0)));
        assertFalse(p.contains(new Point(200, 0, 0)));
        obj.addProperty("expression", "0&!1");
        assertEquals(5, new RegionPredicate(obj, true).bounds().x().high());
        parts.set(0, Json.parse(sphere(new Point(0, 0, 0), MAX)));
        obj.addProperty("expression", "!0");
        assertTrue(new RegionPredicate(obj, true).bounds().empty());
        for (String bad : List.of("", "11", "1 0", "0&", "0)", "(!1", "1||0")) {
            obj.addProperty("expression", bad);
            assertThrows(IllegalArgumentException.class, () -> new RegionPredicate(obj, true));
        }
    }

    @Test
    public void precedenceShortCircuitAndDeepNesting() {
        var q = new Point(0, 0, 0);
        var obj = new JsonObject();
        for (int mask = 0; mask < 16; mask++) {
            var parts = new JsonArray();
            boolean[] hit = new boolean[4];
            for (int i = 0; i < 4; i++) {
                hit[i] = (mask & (1 << i)) != 0;
                parts.add(Json.parse(sphere(hit[i] ? q : new Point(2, 0, 0), "0")));
            }
            obj.add("primitives", parts);
            obj.addProperty("expression", "!0&1|2&(!3|!1)");
            assertEquals(
                    (!hit[0] && hit[1]) || (hit[2] && (!hit[3] || !hit[1])),
                    new RegionPredicate(obj, false).contains(q));
        }
        obj.addProperty(
                "expression", "!(".repeat(8192) + "0" + ")".repeat(8192) + "&0".repeat(8192));
        assertTrue(new RegionPredicate(obj, false).contains(q));
    }
}
