package io.github.imsng1019.ahr.data;

import com.google.gson.*;

import java.util.*;

public final class Presentation {
    private Presentation() {}

    public static final List<String> EFFECTS =
            List.of("Genshin", "Fade", "Wipe", "Zoom", "Typewriter", "Instant");

    public static double position(JsonObject node, JsonObject global, int axis) {
        var value = node.has("position") ? node.get("position") : global.get("position");
        if (value != null) return value.getAsJsonArray().get(axis).getAsDouble();
        return axis == 0 ? 0.5 : 0.25;
    }

    public static double parameter(JsonObject motion, String key) {
        double preset =
                switch (key) {
                    case "enterMs", "exitMs" -> 400;
                    case "holdMs" -> 1800;
                    case "offsetLines" -> 0.75;
                    case "fromScale" -> 0.90;
                    case "graphemesPerSecond" -> 14;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown animation parameter " + key);
                };
        return number(motion, key, preset);
    }

    public static double number(JsonObject o, String key, double fallback) {
        if (!o.has(key)) return fallback;
        var v = o.get(key);
        if (!v.isJsonPrimitive()
                || !v.getAsJsonPrimitive().isNumber()
                || !Double.isFinite(v.getAsDouble()))
            throw new IllegalArgumentException("Expected a finite number for " + key);
        return v.getAsDouble();
    }

    public static double interval(JsonObject config) {
        double n = number(config, "detectionIntervalSeconds", 1);
        if (n < 0.0625 || n > 16)
            throw new IllegalArgumentException(
                    "Detection interval must be between 0.0625 and 16 seconds");
        return n;
    }

    public static void validateHints(JsonObject o) {
        for (String k : List.of("useRichText", "showFullPath", "showAncestorOnExit", "enabled"))
            if (o.has(k)) Json.bool(o.get(k));
        if (o.has("separator")) Json.string(o.get("separator"));
        if (o.has("position")) {
            for (var v : Json.tuple(o.get("position"), 2)) {
                if (!v.isJsonPrimitive()
                        || !v.getAsJsonPrimitive().isNumber()
                        || !(v.getAsDouble() >= 0 && v.getAsDouble() <= 1))
                    throw new IllegalArgumentException("Hint position must be in [0,1]");
            }
        }
        if (o.has("animation")) validateAnimation(o.getAsJsonObject("animation"));
        if (o.has("richText")) Json.object(o.getAsJsonObject("richText"), "translations");
    }

    private static void validateAnimation(JsonObject a) {
        if (!EFFECTS.contains(Json.string(a.get("type"))))
            throw new IllegalArgumentException("Unknown animation type");
        for (String k :
                List.of(
                        "enterMs",
                        "holdMs",
                        "exitMs",
                        "offsetLines",
                        "fromScale",
                        "graphemesPerSecond")) {
            double v = number(a, k, 1);
            if (v < 0 || ((k.equals("graphemesPerSecond") || k.equals("fromScale")) && v == 0))
                throw new IllegalArgumentException("Invalid animation parameter " + k);
        }
        if (a.has("origin")
                && !Set.of("left", "right", "center").contains(Json.string(a.get("origin"))))
            throw new IllegalArgumentException("Unknown wipe origin");
    }

    public static JsonObject animation(JsonObject node, JsonObject global) {
        var local = Json.object(global, "animation");
        var selected = Json.object(node, "animation");
        String type =
                selected.has("type")
                        ? Json.string(selected.get("type"))
                        : local.has("type") ? Json.string(local.get("type")) : "Genshin";
        var result = new JsonObject();
        result.addProperty("type", type);
        if (local.has("type") && type.equals(Json.string(local.get("type"))))
            local.entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
        selected.entrySet().forEach(e -> result.add(e.getKey(), e.getValue()));
        return result;
    }
}
