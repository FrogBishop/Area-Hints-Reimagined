package io.github.imsng1019.ahr.client;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.AreaHints;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

final class ClientSettings {
    private final Path file =
            FabricLoader.getInstance().getConfigDir().resolve("area-hints-reimagined.json");
    private JsonObject config = new JsonObject();

    ClientSettings() {
        try {
            if (Files.exists(file)) {
                var data = Json.parse(Files.readString(file)).getAsJsonObject();
                validate(data);
                config = data;
            }
        } catch (IOException | RuntimeException e) {
            AreaHints.LOG.error("Could not load " + file, e);
        }
    }

    private static void validate(JsonObject data) {
        Presentation.interval(data);
        if (data.has("enabled")) Json.bool(data.get("enabled"));
        if (data.has("showPrimitiveOutlines")) Json.bool(data.get("showPrimitiveOutlines"));
        Presentation.validateHints(Json.object(data, "hints"));
    }

    JsonObject copy() {
        return config.deepCopy();
    }

    void saveEdits(JsonObject before, JsonObject options, JsonObject hints) {
        // Only commit edits from this page. A client command may have changed
        // another preference while the page or a picker was open.
        var next = copy();
        copyChanged(
                before,
                options,
                next,
                List.of("enabled", "showPrimitiveOutlines", "detectionIntervalSeconds"));
        var oldHints = Json.object(before, "hints");
        if (!oldHints.equals(hints)) {
            var current = Json.object(next, "hints");
            copyChanged(
                    oldHints,
                    hints,
                    current,
                    List.of(
                            "useRichText",
                            "showFullPath",
                            "showAncestorOnExit",
                            "separator",
                            "position",
                            "animation"));
            Json.putOptionalObject(next, "hints", current);
        }
        if (!config.equals(next)) save(next);
    }

    private static void copyChanged(
            JsonObject before, JsonObject edited, JsonObject into, List<String> keys) {
        for (String key : keys) {
            var value = edited.get(key);
            if (!Objects.equals(before.get(key), value)) {
                if (value == null) into.remove(key);
                else into.add(key, value);
            }
        }
    }

    void save(JsonObject value) {
        validate(value);
        try {
            Json.write(file, Json.GSON.toJson(value) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config = value;
    }

    double interval() {
        return Presentation.interval(config);
    }

    boolean enabled() {
        return !config.has("enabled") || Json.bool(config.get("enabled"));
    }

    boolean showOutlines() {
        return config.has("showPrimitiveOutlines")
                && Json.bool(config.get("showPrimitiveOutlines"));
    }

    JsonObject hints() {
        return Json.object(config, "hints");
    }

    JsonElement setting(JsonObject world, String key, JsonElement fallback) {
        var save = Json.object(world, "hints");
        if (save.has(key)) return save.get(key);
        var local = hints();
        return local.has(key) ? local.get(key) : fallback;
    }
}
