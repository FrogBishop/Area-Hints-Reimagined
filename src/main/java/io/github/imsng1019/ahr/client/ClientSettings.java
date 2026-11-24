package io.github.imsng1019.ahr.client;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.AreaHints;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;

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
        Presentation.validateHints(Json.object(data, "hints"));
    }

    JsonObject copy() {
        return config.deepCopy();
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
