package io.github.imsng1019.ahr.geometry;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.Json;

import java.math.BigInteger;

public record Point(int x, int y, int z) {
    public static Point floor(double x, double y, double z) {
        return new Point((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public static Point read(JsonElement e) {
        var a = Json.tuple(e, 3);
        return new Point(Json.coord(a.get(0)), Json.coord(a.get(1)), Json.coord(a.get(2)));
    }

    public static Point readXZ(JsonElement e, int y) {
        var a = Json.tuple(e, 2);
        return new Point(Json.coord(a.get(0)), y, Json.coord(a.get(1)));
    }

    public JsonArray json() {
        return Json.numbers(x, y, z);
    }

    public JsonArray xz() {
        return Json.numbers(x, z);
    }

    public BigInteger distanceSquared(Point other, boolean horizontal) {
        return square((long) x - other.x)
                .add(square((long) z - other.z))
                .add(horizontal ? BigInteger.ZERO : square((long) y - other.y));
    }

    private static BigInteger square(long v) {
        return BigInteger.valueOf(v).pow(2);
    }
}
