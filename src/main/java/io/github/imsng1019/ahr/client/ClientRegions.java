package io.github.imsng1019.ahr.client;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.*;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class ClientRegions {
    Map<String, WorldFiles.Dimension> active = Map.of();
    private final Map<String, WorldFiles.Dimension> pending = new HashMap<>();
    private final Map<String, String> advertised = new HashMap<>();
    JsonObject settings = new JsonObject();
    private JsonObject nextSettings = new JsonObject();
    private Path cache;
    boolean ready, author;

    void reset() {
        active = Map.of();
        advertised.clear();
        pending.clear();
        settings = new JsonObject();
        ready = false;
        author = false;
        cache = null;
    }

    WorldFiles.Dimension dimension(String id) {
        return active.getOrDefault(id, WorldFiles.EMPTY);
    }

    void manifest(Minecraft mc, JsonObject msg) {
        Path serverCache = null;
        var server = mc.getCurrentServer();
        if (!mc.hasSingleplayerServer() && server != null) {
            var address = ServerAddress.parseString(server.ip);
            String host = address.getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
            String normalized =
                    (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                            + ":"
                            + address.getPort();
            serverCache =
                    mc.gameDirectory
                            .toPath()
                            .resolve("cache")
                            .resolve(WorldFiles.DIRECTORY)
                            .resolve("servers")
                            .resolve(Json.digest(normalized));
        }
        manifest(msg, serverCache, ClientRegions::send);
    }

    void manifest(JsonObject msg, Path serverCache, Consumer<JsonObject> requestFile) {
        ready = false;
        author = Json.bool(msg.get("author"));
        advertised.clear();
        pending.clear();
        cache = serverCache;
        nextSettings = msg.getAsJsonObject("settings");
        Presentation.validateHints(Json.object(nextSettings, "hints"));
        for (var entry : msg.getAsJsonObject("hashes").entrySet()) {
            String id = entry.getKey(), hash = Json.string(entry.getValue());
            advertised.put(id, hash);
            var known = active.get(id);
            if (known != null && known.digest().equals(hash)) {
                pending.put(id, known);
                continue;
            }
            if (cache != null) {
                Path f = WorldFiles.file(cache, id);
                try {
                    if (Files.exists(f)) {
                        String text = Files.readString(f);
                        if (Json.digest(text).equals(hash)) {
                            pending.put(id, WorldFiles.Dimension.read(text, known));
                            continue;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    AreaHints.LOG.warn("Could not reuse cached dimension {}", id, e);
                }
            }
            var request = Wire.message("get");
            request.addProperty("dimension", id);
            request.addProperty("digest", hash);
            requestFile.accept(request);
        }
        activate();
    }

    void file(JsonObject msg) {
        String id = Wire.field(msg, "dimension"), hash = Wire.field(msg, "digest");
        if (!hash.equals(advertised.get(id))) return;
        var file = WorldFiles.Dimension.read(Wire.field(msg, "text"), active.get(id));
        if (!hash.equals(file.digest()))
            throw new IllegalArgumentException("Region file digest mismatch for " + id);
        pending.put(id, file);
        if (cache != null) {
            try {
                Json.write(WorldFiles.file(cache, id), file.text());
            } catch (IOException e) {
                AreaHints.LOG.warn("Could not cache dimension {}", id, e);
            }
        }
        activate();
    }

    private void activate() {
        if (pending.size() != advertised.size()) return;
        active = Map.copyOf(pending);
        settings = nextSettings;
        ready = true;
    }

    static void send(JsonObject msg) {
        Wire.parts(msg.toString(), part -> ClientPlayNetworking.send(new Wire.ToServer(part)));
    }
}
