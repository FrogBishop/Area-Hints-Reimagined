package io.github.imsng1019.ahr.client;

import static org.junit.Assert.*;

import io.github.imsng1019.ahr.data.Json;
import io.github.imsng1019.ahr.geometry.Point;

import org.junit.Test;

import java.util.*;

public class PrimitiveDraftTest {
    @Test
    public void centerOnlyRoundTripAndReplacementRadius() {
        var json =
                Json.parse("{\"center\":[5,6],\"radiusSquared\":3,\"y\":\"unbounded\"}")
                        .getAsJsonObject();
        var d = new PrimitiveDraft("cylinder", json, 78);
        assertEquals(List.of(new Point(5, 78, 6)), d.points);
        assertEquals(json, d.save(false).get("cylinder"));
        d.mark(new Point(8, -100, 10));
        assertFalse(d.mark(new Point(0, 0, 0)));
        assertEquals(25, d.save(false).getAsJsonObject("cylinder").get("radiusSquared").getAsInt());
        json.add("y", Json.numbers(-34, 10));
        assertEquals(-34, new PrimitiveDraft("cylinder", json, 78).points.getFirst().y());
        var sphere = new PrimitiveDraft("sphere", null, 0);
        sphere.mark(new Point(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE));
        sphere.mark(new Point(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> sphere.save(false));
    }

    @Test
    public void unusedVerticesCanBeKeptOrCompactedAfterFaceValidation() {
        var d = new PrimitiveDraft("polyhedron", null, 0);
        d.points.addAll(
                List.of(
                        new Point(0, 0, 0),
                        new Point(9, 9, 9),
                        new Point(1, 0, 0),
                        new Point(0, 1, 0),
                        new Point(0, 0, 1)));
        d.faces.addAll(
                List.of(
                        new Point(0, 2, 3),
                        new Point(0, 2, 4),
                        new Point(0, 3, 4),
                        new Point(2, 3, 4)));
        assertTrue(d.unused());
        assertThrows(IllegalArgumentException.class, () -> d.deletePoint(0));
        assertEquals(
                5, d.save(false).getAsJsonObject("polyhedron").getAsJsonArray("vertices").size());
        var compact = d.save(true);
        assertEquals(4, compact.getAsJsonObject("polyhedron").getAsJsonArray("vertices").size());
        assertEquals(5, d.points.size());
        d.deletePoint(1);
        assertFalse(d.unused());
        assertEquals(compact, d.save(false));
    }
}
