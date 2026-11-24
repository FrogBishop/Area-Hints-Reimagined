package io.github.imsng1019.ahr.client;

import static org.junit.Assert.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.geometry.*;

import net.minecraft.world.phys.Vec3;

import org.junit.Test;

import java.util.*;

public class OutlinesTest {
    private static PrimitiveOutline shape(String s) {
        return PrimitiveOutline.read(Json.parse(s).getAsJsonObject());
    }

    private static OutlineFrame frame(double x, double y, double z, double range) {
        return new OutlineFrame(new Vec3(x, y, z), range);
    }

    @Test
    public void sphereDrawsOnlyThreeFixedGreatCircles() {
        var sphere = shape("{\"sphere\":{\"center\":[10,20,30],\"radiusSquared\":169}}");
        var full = frame(10, 20, 30, 32);
        sphere.draw(full, 1);

        var expected = frame(10, 20, 30, 32);
        var center = new Vec3(10, 20, 30);
        expected.circle(center, 13, OutlineFrame.Plane.XY, 1);
        expected.circle(center, 13, OutlineFrame.Plane.XZ, 1);
        expected.circle(center, 13, OutlineFrame.Plane.YZ, 1);
        assertEquals(expected.lines.size(), full.lines.size());
        assertEquals(new HashSet<>(expected.lines), new HashSet<>(full.lines));

        // (3, 4, 12) lies on the radius-13 sphere, away from all three planes.
        var betweenRings = frame(13, 24, 42, 1);
        sphere.draw(betweenRings, 1);
        assertTrue(betweenRings.lines.isEmpty());
    }

    @Test
    public void prismHoleCancelsSubdividedBridgeAndKeepsBothRims() {
        String text =
                """
                {"prism":{"vertices":[[0,0],[0,12],[12,0],[8,2],[4,6],[4,2],[8,2],[10,1],[12,0]],"y":[60,70],"insideAtInfinity":false}}
                """;
        var f = frame(6, 65, 6, 32);
        var p = (PrimitiveOutline.Prism) shape(text);
        assertEquals(6, p.rim.size());
        p.draw(f, 1);
        assertEquals(18, f.lines.size());
        for (var e : p.rim) {
            assertFalse(
                    Set.of(e.a(), e.b()).equals(Set.of(new Point(12, 0, 0), new Point(8, 0, 2))));
            assertNotEquals(new Point(10, 0, 1), e.a());
            assertNotEquals(new Point(10, 0, 1), e.b());
        }
        var far = frame(6, 65, 6, 32);
        shape(text.replace("false", "true")).draw(far, 1);
        assertEquals(new HashSet<>(f.lines), new HashSet<>(far.lines));
    }

    @Test
    public void unboundedCylinderHasOpenEndsAndANonsquareRadius() {
        var f = frame(0, 1000, 0, 8);
        shape("{\"cylinder\":{\"center\":[0,0],\"radiusSquared\":3,\"y\":\"unbounded\"}}")
                .draw(f, 2);
        assertTrue(f.lines.size() > 8);
        int vertical = 0;
        for (var line : f.lines) {
            Vec3 a = line.from(), b = line.to();
            assertEquals(3, a.x * a.x + a.z * a.z, 1e-10);
            if (a.y != b.y) {
                vertical++;
                assertEquals(992, a.y, 0);
                assertEquals(1008, b.y, 0);
            } else assertEquals(1000, a.y, 0);
        }
        assertEquals(8, vertical);
    }

    @Test
    public void triangleEdgesAppearOnceAndUnusedPointsAddNoEdge() {
        var f = frame(0, 0, 0, 32);
        shape(
                        """
                        {"polyhedron":{"vertices":[[0,0,0],[4,0,0],[0,4,0],[0,0,4],[20,20,20]],
                        "faces":[[0,2,1],[3,1,0],[0,3,2],[2,3,1]],"insideAtInfinity":false}}
                        """)
                .draw(f, 3);
        assertEquals(6, f.lines.size());
        for (var e : f.lines) assertTrue(e.from().length() <= 4 && e.to().length() <= 4);
    }

    @Test
    public void clipsThroughViewAtInt32ExtremesAndDrawsUint64Radius() {
        var f = frame(Integer.MAX_VALUE - 2, 0, 0, 8);
        f.line(
                new Vec3(Integer.MIN_VALUE, 1, 0),
                new Vec3((double) Integer.MAX_VALUE + 20, 1, 0),
                1);
        assertEquals(1, f.lines.size());
        assertEquals(f.camera.x - 8, f.lines.getFirst().from().x, 1e-5);
        assertEquals(f.camera.x + 8, f.lines.getFirst().to().x, 1e-5);
        f.lines.clear();
        shape("{\"sphere\":{\"center\":[-2147483648,0,0],\"radiusSquared\":18446744073709551615}}")
                .draw(f, 1);
        assertFalse(f.lines.isEmpty());
        for (var line : f.lines)
            for (var p : List.of(line.from(), line.to())) {
                assertTrue(
                        Math.abs(p.x - f.camera.x) <= 8.00001
                                && Math.abs(p.y) <= 8.00001
                                && Math.abs(p.z) <= 8.00001);
                assertEquals(
                        4294967296d,
                        Math.hypot(p.x - Integer.MIN_VALUE, Math.hypot(p.y, p.z)),
                        .07);
            }
    }

    @Test
    public void editingReplacesOnlyItsRegionAndDraftMeasurementsDoNotMutateMarks() {
        var json =
                Json.parse(
                                """
                                {"children":{"A":{"name":{"fallback":"A"},"predicate":{"expression":"!0&0","primitives":[
                                  {"sphere":{"center":[-5,0,0],"radiusSquared":0}},{"sphere":{"center":[5,0,0],"radiusSquared":0}}]},"aabb":"empty",
                                  "children":{"B":{"name":{"fallback":"B"},"hint":{"enabled":false},"predicate":{"expression":"0","primitives":[
                                  {"sphere":{"center":[10,0,0],"radiusSquared":0}}]},"aabb":{"x":[10,10],"y":[0,0],"z":[0,0]}}}}}}
                                """)
                        .getAsJsonObject();
        var tree = new RegionTree(json);
        var outlines = new WorldOutlines();
        var f = frame(0, 0, 0, 32);
        outlines.collect(f, tree, null, null);
        assertEquals(9, f.lines.size());
        var pending = new PrimitiveDraft("sphere", null, 0);
        pending.mark(new Point(0, 1, 0));
        var editing =
                new WorldOutlines.Editing(
                        List.of("A"),
                        List.of(),
                        -1,
                        PrimitiveOutline.from(pending),
                        List.copyOf(pending.points),
                        true,
                        true);
        f.lines.clear();
        outlines.collect(f, tree, editing, new Point(0, 4, 4));
        assertEquals(3, f.lines.stream().filter(e -> e.color() == WorldOutlines.SAVED).count());
        assertTrue(
                f.lines.stream()
                        .anyMatch(e -> e.color() == WorldOutlines.GUIDE && e.from().y() > 5));
        assertEquals(1, pending.points.size());
        f.lines.clear();
        outlines.collect(f, null, null, null);
        assertTrue(f.lines.isEmpty());
    }
}
