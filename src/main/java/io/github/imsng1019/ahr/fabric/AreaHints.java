package io.github.imsng1019.ahr.fabric;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.*;

import java.io.IOException;
import java.util.*;

public final class AreaHints implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Area Hints Reimagined");
    private static final Map<MinecraftServer, WorldFiles> WORLDS = new IdentityHashMap<>();

    public static WorldFiles world(MinecraftServer server) {
        return Objects.requireNonNull(WORLDS.get(server));
    }

    public static boolean author(MinecraftServer server, ServerPlayer p) {
        return server.isSingleplayerOwner(p.nameAndId())
                || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void onInitialize() {
        Wire.register();
        SaveCommands.register();
        ServerLifecycleEvents.SERVER_STARTING.register(
                server -> {
                    var world = new WorldFiles(server.getWorldPath(LevelResource.ROOT));
                    WORLDS.put(server, world);
                    try {
                        world.reload();
                    } catch (IOException | RuntimeException e) {
                        LOG.error("Could not load regions", e);
                    }
                });
        ServerLifecycleEvents.SERVER_STOPPED.register(WORLDS::remove);
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> manifest(server, handler.player));
        var incoming =
                new IdentityHashMap<
                        net.minecraft.server.network.ServerGamePacketListenerImpl, Wire.Incoming>();
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> incoming.remove(handler));
        ServerPlayNetworking.registerGlobalReceiver(
                Wire.ToServer.TYPE,
                (packet, ctx) -> {
                    var connection = ctx.player().connection;
                    // Fetch requests fit in one packet. Only world authors may
                    // accumulate an edit across packets.
                    if (!packet.part().last() && !author(ctx.server(), ctx.player())) {
                        incoming.remove(connection);
                        return;
                    }
                    var buffer = incoming.computeIfAbsent(connection, k -> new Wire.Incoming());
                    String text = buffer.accept(packet.part());
                    if (text != null) receive(ctx.server(), ctx.player(), text);
                });
    }

    private static void send(ServerPlayer player, JsonObject body) {
        Wire.parts(
                body.toString(),
                part -> ServerPlayNetworking.send(player, new Wire.ToClient(part)));
    }

    private static void manifest(MinecraftServer server, ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, Wire.ToClient.TYPE)) return;
        var msg = Wire.message("manifest");
        var hashes = new JsonObject();
        var world = world(server);
        world.dimensions().forEach((id, data) -> hashes.addProperty(id, data.digest()));
        msg.add("hashes", hashes);
        msg.add("settings", world.settings());
        msg.addProperty("author", author(server, player));
        send(player, msg);
    }

    public static void publish(MinecraftServer server) {
        for (var player : server.getPlayerList().getPlayers()) manifest(server, player);
    }

    private static void receive(MinecraftServer server, ServerPlayer player, String body) {
        var reply = Wire.message("result");
        try {
            var msg = Json.parse(body).getAsJsonObject();
            var world = world(server);
            String kind = Wire.field(msg, "kind");
            if (kind.equals("get")) {
                String id = Wire.field(msg, "dimension");
                var data = world.dimensions().get(id);
                if (data == null || !data.digest().equals(Wire.field(msg, "digest"))) {
                    manifest(server, player);
                    return;
                }
                var file = Wire.message("file");
                file.addProperty("dimension", id);
                file.addProperty("digest", data.digest());
                file.addProperty("text", data.text());
                send(player, file);
                return;
            }
            reply.addProperty("ticket", Wire.field(msg, "ticket"));
            if (!author(server, player))
                throw new IllegalArgumentException("Editing requires world author permission");
            switch (kind) {
                case "settings" ->
                        world.saveHints(Wire.field(msg, "digest"), msg.getAsJsonObject("hints"));
                case "edit" -> {
                    String id = player.level().dimension().identifier().toString();
                    if (!id.equals(Wire.field(msg, "dimension")))
                        throw new IllegalArgumentException("The current dimension changed");
                    var original = msg.has("original") ? Json.path(msg.get("original")) : null;
                    var path = Json.path(msg.get("path"));
                    world.edit(
                            id,
                            Wire.field(msg, "digest"),
                            root ->
                                    WorldFiles.put(
                                            root, original, path, msg.getAsJsonObject("fields")));
                }
                default -> throw new IllegalArgumentException("Unknown request");
            }
            publish(server);
            reply.addProperty("ok", true);
        } catch (RuntimeException | IOException e) {
            reply.addProperty("ok", false);
            reply.addProperty("error", Objects.toString(e.getMessage(), "Invalid request"));
        }
        send(player, reply);
    }
}
