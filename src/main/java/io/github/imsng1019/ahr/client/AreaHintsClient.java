package io.github.imsng1019.ahr.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.*;
import io.github.imsng1019.ahr.geometry.Point;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;

import java.util.*;

public final class AreaHintsClient implements ClientModInitializer {
    final ClientSettings settings = new ClientSettings();
    final ClientRegions regions = new ClientRegions();
    private final Wire.Incoming incoming = new Wire.Incoming();
    final Detection detection = new Detection();
    final HintHud hud = new HintHud();
    final RegionEditor editor = new RegionEditor(this);
    private final WorldOutlines outlines = new WorldOutlines();
    private String lastDimension;

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static String dimension() {
        return mc().level.dimension().identifier().toString();
    }

    static Point point() {
        var p = mc().player;
        return Point.floor(p.getX(), p.getY(), p.getZ());
    }

    static void message(Component text) {
        if (mc().player != null) mc().player.sendSystemMessage(text);
    }

    @Override
    public void onInitializeClient() {
        var group = KeyMapping.Category.register(Wire.id("controls"));
        var menu = key("menu", group);
        var mark = key("mark", group);
        HudElementRegistry.addLast(Wire.id("hint"), (graphics, tick) -> hud.render(graphics));
        outlines.register(this);
        ClientTickEvents.END_CLIENT_TICK.register(
                mc -> {
                    while (menu.consumeClick())
                        if (mc.player != null && mc.gui.screen() == null) editor.open();
                    while (mark.consumeClick())
                        if (mc.player != null && mc.gui.screen() == null) editor.mark(point());
                });
        ClientTickEvents.END_LEVEL_TICK.register(
                level -> {
                    if (mc().player == null
                            || mc().isPaused()
                            || !level.tickRateManager().runsNormally()) return;
                    String id = dimension();
                    if (!id.equals(lastDimension)) {
                        if (lastDimension != null) editor.dimensionChanged();
                        hud.clear();
                        detection.reset();
                        lastDimension = id;
                    }
                    if (!regions.ready
                            || !settings.enabled()
                            || !detection.advance(settings.interval())) return;
                    var winner =
                            detection.sample(
                                    regions.dimension(id).tree(),
                                    point(),
                                    settings.setting(
                                                    regions.settings,
                                                    "showAncestorOnExit",
                                                    new JsonPrimitive(false))
                                            .getAsBoolean());
                    if (winner != null) hud.show(mc(), winner, settings, regions.settings);
                });
        UseBlockCallback.EVENT.register(
                (player, world, hand, hit) -> {
                    if (!world.isClientSide() || player != mc().player || !editor.marking())
                        return InteractionResult.PASS;
                    var p = hit.getBlockPos();
                    editor.mark(new Point(p.getX(), p.getY(), p.getZ()));
                    return InteractionResult.FAIL;
                });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, mc) -> {
                    regions.reset();
                    outlines.clear();
                    incoming.clear();
                    detection.reset();
                    hud.clear();
                    editor.reset();
                    lastDimension = null;
                });
        ClientPlayNetworking.registerGlobalReceiver(
                Wire.ToClient.TYPE,
                (packet, ctx) -> {
                    try {
                        String body = incoming.accept(packet.part());
                        if (body == null) return;
                        var msg = Json.parse(body).getAsJsonObject();
                        switch (Wire.field(msg, "kind")) {
                            case "manifest" -> regions.manifest(ctx.client(), msg);
                            case "file" -> regions.file(msg);
                            case "result" -> editor.result(msg);
                        }
                    } catch (RuntimeException e) {
                        AreaHints.LOG.error("Could not apply region data", e);
                        message(
                                Component.literal(
                                        Objects.toString(e.getMessage(), "Invalid region data")));
                    }
                });
        commands();
    }

    private static KeyMapping key(String name, KeyMapping.Category group) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.area_hints_reimagined." + name,
                        InputConstants.UNKNOWN.getValue(),
                        group));
    }

    void enable(boolean value) {
        var data = settings.copy();
        data.addProperty("enabled", value);
        settings.save(data);
        detection.reset();
        hud.clear();
    }

    private void commands() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, context) -> {
                    var root = literal("ahr");
                    var hints =
                            literal("hints")
                                    .executes(
                                            c -> {
                                                message(
                                                        MenuPage.tr(
                                                                settings.enabled()
                                                                        ? "hints.on"
                                                                        : "hints.off"));
                                                return 1;
                                            });
                    for (var state : List.of("on", "off"))
                        hints.then(
                                literal(state)
                                        .executes(
                                                c -> {
                                                    enable(state.equals("on"));
                                                    message(MenuPage.tr("hints." + state));
                                                    return 1;
                                                }));
                    root.then(hints);
                    root.then(
                            literal("here")
                                    .executes(
                                            c -> {
                                                if (!regions.ready) {
                                                    message(MenuPage.tr("unavailable"));
                                                    return 0;
                                                }
                                                var p = point();
                                                message(
                                                        Component.literal(
                                                                dimension() + " " + p.json()));
                                                for (var n :
                                                        regions.dimension(dimension())
                                                                .tree()
                                                                .matches(p)) describe(n);
                                                return 1;
                                            }));
                    root.then(
                            literal("list")
                                    .executes(c -> list(List.of()))
                                    .then(
                                            argument("path", StringArgumentType.greedyString())
                                                    .executes(
                                                            c -> {
                                                                try {
                                                                    return list(
                                                                            Json.path(
                                                                                    Json.parse(
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            c,
                                                                                                            "path"))));
                                                                } catch (RuntimeException e) {
                                                                    message(
                                                                            Component.literal(
                                                                                    Objects
                                                                                            .toString(
                                                                                                    e
                                                                                                            .getMessage(),
                                                                                                    "Invalid"
                                                                                                        + " path")));
                                                                    return 0;
                                                                }
                                                            })));
                    dispatcher.register(root);
                });
    }

    private static void describe(RegionTree.Node n) {
        message(Component.literal(Json.path(n.path) + "  " + HintHud.plain(mc(), n)));
    }

    private int list(List<String> path) {
        if (!regions.ready) {
            message(MenuPage.tr("unavailable"));
            return 0;
        }
        var children = regions.dimension(dimension()).tree().find(path).children;
        if (children.isEmpty()) message(MenuPage.tr("noChildren"));
        children.values().forEach(AreaHintsClient::describe);
        return 1;
    }
}
