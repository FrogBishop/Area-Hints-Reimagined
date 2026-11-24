package io.github.imsng1019.ahr.client;

import static org.junit.Assert.*;

import io.github.imsng1019.ahr.data.Json;
import io.github.imsng1019.ahr.geometry.Bounds;
import io.github.imsng1019.ahr.geometry.Point;

import net.minecraft.world.phys.Vec3;

import org.junit.Test;

import java.util.List;

public class SurfacesTest {
    private static OutlineFrame prism(String walk, boolean infinity, Vec3 camera, double range) {
        var shape =
                PrimitiveOutline.read(
                        Json.parse(
                                        "{\"prism\":{\"vertices\":"
                                                + walk
                                                + ",\"insideAtInfinity\":"
                                                + infinity
                                                + ",\"y\":[0,2]}}")
                                .getAsJsonObject());
        var frame = new OutlineFrame(camera, range);
        shape.draw(frame, WorldOutlines.SAVED);
        return frame;
    }

    private static double triangleXZ(Vec3 a, Vec3 b, Vec3 c) {
        return Math.abs((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x)) / 2;
    }

    private static double capArea(OutlineFrame frame, double y) {
        double area = 0;
        for (var face : frame.faces) {
            if (face.a().y == y && face.b().y == y && face.c().y == y && face.d().y == y)
                area +=
                        triangleXZ(face.a(), face.b(), face.c())
                                + triangleXZ(face.a(), face.c(), face.d());
        }
        return area;
    }

    @Test
    public void holedAndInvertedPrismCapsRespectCrossingParity() {
        String hole = "[[0,0],[10,0],[10,10],[0,10],[0,0],[2,2],[4,2],[4,4],[2,4],[2,2],[1,1]]";
        String bow = "[[0,0],[10,10],[10,0],[0,10]]";
        var view = new Vec3(5, 1, 5);
        assertEquals(96, capArea(prism(hole, false, view, 10), 0), 1e-9);
        assertEquals(304, capArea(prism(hole, true, view, 10), 2), 1e-9);
        assertEquals(50, capArea(prism(bow, false, view, 10), 0), 1e-9);
        assertEquals(350, capArea(prism(bow, true, view, 10), 2), 1e-9);
        var insideHole = new Vec3(3, 0, 3);
        assertEquals(0, capArea(prism(hole, false, insideHole, .5), 0), 0);
        assertEquals(1, capArea(prism(hole, true, insideHole, .5), 0), 1e-9);
        assertEquals(4, capArea(prism(hole, true, new Vec3(100, 0, 0), 1), 0), 1e-9);
    }

    @Test
    public void cylindersUseRealCapsAndOpenUnboundedSides() {
        var center = new Point(0, 0, 0);
        var f = new OutlineFrame(new Vec3(0, 7, 0), 2);
        new PrimitiveOutline.Radial(center, new Bounds.Axis(7, 7), 4294967295.0).draw(f, 1);
        assertEquals(16, capArea(f, 7), 1e-9);
        assertEquals(1, f.faces.size());
        f = new OutlineFrame(new Vec3(0, 7, 0), 5);
        new PrimitiveOutline.Radial(center, Bounds.ANY, 2.0).draw(f, 1);
        assertFalse(f.faces.isEmpty());
        for (var face : f.faces) {
            assertTrue(face.a().y != face.c().y);
            for (var p : List.of(face.a(), face.b(), face.c(), face.d()))
                assertEquals(2, Math.hypot(p.x, p.z), 1e-9);
        }
        var openPrism =
                PrimitiveOutline.read(
                        Json.parse(
                                        """
                                        {"prism":{"vertices":[[0,0],[2,0],[2,2],[0,2]],"insideAtInfinity":false,"y":"unbounded"}}
                                        """)
                                .getAsJsonObject());
        f.faces.clear();
        openPrism.draw(f, 1);
        assertEquals(4, f.faces.size());
        for (var face : f.faces) assertTrue(face.a().y != face.c().y);
    }

    @Test
    public void aLargeSphereShowsItsClippedSurfaceBetweenTheThreeCircles() {
        var c = new Vec3(300000000, 400000000, 1200000000);
        var f = new OutlineFrame(c, 8);
        new PrimitiveOutline.Radial(new Point(0, 0, 0), null, 1300000000.0).draw(f, 1);
        assertTrue(f.lines.isEmpty());
        assertFalse(f.faces.isEmpty());
        for (var face : f.faces)
            for (var p : List.of(face.a(), face.b(), face.c(), face.d())) {
                assertEquals(1300000000.0, p.length(), .2);
                assertTrue(
                        Math.abs(p.x - c.x) < 8.00001
                                && Math.abs(p.y - c.y) < 8.00001
                                && Math.abs(p.z - c.z) < 8.00001);
            }
    }

    @Test
    public void aPolyhedronFaceCanCoverTheViewWithAllVerticesOutside() {
        var vertices =
                List.of(
                        new Point(-2000000000, 0, -2000000000),
                        new Point(2000000000, 0, -2000000000),
                        new Point(0, 0, 2000000000));
        for (var order : List.of(new Point(0, 1, 2), new Point(2, 1, 0))) {
            var f = new OutlineFrame(Vec3.ZERO, 2);
            new PrimitiveOutline.Polyhedron(vertices, List.of(order)).draw(f, 1);
            assertEquals(16, capArea(f, 0), 1e-5);
        }
    }
}
