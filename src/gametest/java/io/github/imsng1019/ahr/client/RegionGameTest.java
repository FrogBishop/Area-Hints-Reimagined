package io.github.imsng1019.ahr.client;

import com.google.gson.*;

import io.github.imsng1019.ahr.fabric.AreaHints;
import io.github.imsng1019.ahr.geometry.Point;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import java.util.*;

public final class RegionGameTest implements FabricClientGameTest {
    static AreaHintsClient mod() {
        return FabricLoader.getInstance()
                .getEntrypoints("client", ClientModInitializer.class)
                .stream()
                .filter(AreaHintsClient.class::isInstance)
                .map(AreaHintsClient.class::cast)
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (var world = ctx.worldBuilder().create()) {
            ctx.waitFor(mc -> mod().regions.ready && mc.player != null, 1200);
            world.getServer()
                    .runCommand(
                            "ahr add [\"Keep\"] \"Test keep\""
                                + " {\"primitives\":[{\"sphere\":{\"center\":[0,64,0],\"radiusSquared\":18446744073709551615}}],\"expression\":\"0\"}");
            ctx.waitFor(
                    mc ->
                            mod().regions
                                    .dimension(AreaHintsClient.dimension())
                                    .tree()
                                    .root
                                    .children
                                    .containsKey("Keep"));
            ctx.runOnClient(
                    mc -> {
                        var tree = mod().regions.dimension(AreaHintsClient.dimension()).tree();
                        if (tree.matches(AreaHintsClient.point()).size() != 1)
                            throw new AssertionError("Local query missed the synchronized sphere");
                        mod().editor.open();
                    });
            ctx.clickScreenButton("New Region");
            checkEditorGrid(ctx);
            ctx.clickScreenButton("Properties");
            var size =
                    ctx.computeOnClient(
                            mc -> {
                                var screen = mc.gui.screen();
                                int[] original = {screen.width, screen.height};
                                screen.resize(320, 134);
                                checkPair(control(screen, "parent"), control(screen, "browse"));
                                return original;
                            });
            ctx.clickScreenButton("›");
            ctx.runOnClient(
                    mc -> {
                        var identifier = (EditBox) control(mc.gui.screen(), "identifier");
                        checkPair(identifier, control(mc.gui.screen(), "fallback"));
                        identifier.setValue("Shell");
                    });
            ctx.clickScreenButton("‹");
            ctx.clickScreenButton("›");
            ctx.runOnClient(
                    mc -> {
                        var screen = mc.gui.screen();
                        var identifier = (EditBox) control(screen, "identifier");
                        if (!identifier.getValue().equals("Shell"))
                            throw new AssertionError("Paging lost the identifier");
                        screen.resize(480, 320);
                        if (!((EditBox) control(screen, "identifier")).getValue().equals("Shell"))
                            throw new AssertionError("Resizing lost the identifier");
                        screen.resize(size[0], size[1]);
                    });
            ctx.runOnClient(mc -> mc.gui.screen().onClose());
            click(ctx, "Add Sphere");
            ctx.runOnClient(
                    mc -> {
                        if (mc.gui.screen() != null || !mod().editor.marking())
                            throw new AssertionError("Expected marking mode");
                        mod().editor.mark(new Point(0, 64, 0));
                        mod().editor.mark(new Point(3, 64, 4));
                        mod().editor.mark(new Point(99, 99, 99));
                        mod().editor.open();
                    });
            click(ctx, "Save Primitive");
            click(ctx, "Save");
            ctx.waitFor(
                    mc ->
                            mod().regions
                                    .dimension(AreaHintsClient.dimension())
                                    .tree()
                                    .root
                                    .children
                                    .containsKey("Shell"));
            world.getServer()
                    .runOnServer(
                            server -> {
                                var node =
                                        AreaHints.world(server)
                                                .dimension("minecraft:overworld")
                                                .tree()
                                                .find(List.of("Shell"));
                                if (!node.predicate.contains(new Point(3, 64, 4))
                                        || node.predicate.contains(new Point(6, 64, 0)))
                                    throw new AssertionError(
                                            "The editor saved an incorrect radius");
                            });
            ctx.runOnClient(
                    mc -> {
                        mc.gui.setScreen(null);
                        mc.getConnection().sendCommand("ahr hints off");
                    });
            ctx.waitFor(mc -> !mod().settings.enabled());
            ctx.runOnClient(mc -> mc.getConnection().sendCommand("ahr hints on"));
            ctx.waitFor(mc -> mod().settings.enabled());
            world.getServer().runCommand("ahr del [\"Shell\"]");
            ctx.waitFor(
                    mc ->
                            !mod().regions
                                    .dimension(AreaHintsClient.dimension())
                                    .tree()
                                    .root
                                    .children
                                    .containsKey("Shell"));
        }
        if (Boolean.getBoolean("ahr.testDedicatedServer")) verifyRemoteCache(ctx);
    }

    private static void checkEditorGrid(ClientGameTestContext ctx) {
        String previous = ctx.computeOnClient(mc -> mc.getLanguageManager().getSelected());
        try {
            setLanguage(ctx, "zh_cn");
            ctx.runOnClient(
                    mc -> {
                        var screen = mc.gui.screen();
                        checkPair(control(screen, "save"), control(screen, "properties"));
                        var expression = (EditBox) control(screen, "expression");
                        checkPair(expression, control(screen, "editPart"));
                        if (!expression.getMessage().getString().equals("布尔表达式"))
                            throw new AssertionError("Expected the Simplified Chinese field label");
                    });
            ctx.takeScreenshot(
                    TestScreenshotOptions.of("region-editor-two-columns-zh")
                            .disableCounterPrefix());
        } finally {
            setLanguage(ctx, previous);
        }
    }

    static void setLanguage(ClientGameTestContext ctx, String code) {
        var reload =
                ctx.computeOnClient(
                        mc -> {
                            mc.getLanguageManager().setSelected(code);
                            return mc.reloadResourcePacks();
                        });
        ctx.waitFor(mc -> reload.isDone() && mc.gui.overlay() == null, 1200);
        reload.join();
        ctx.runOnClient(mc -> mod().editor.open());
    }

    private static AbstractWidget control(Screen screen, String key) {
        String label = MenuPage.tr(key).getString();
        return screen.children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(w -> w.getMessage().getString().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing control: " + label));
    }

    private static void checkPair(AbstractWidget left, AbstractWidget right) {
        if (left.getY() != right.getY()
                || left.getWidth() != right.getWidth()
                || left.getHeight() != right.getHeight()
                || left.getX() + left.getWidth() >= right.getX())
            throw new AssertionError(
                    "Expected two equal-sized controls in the same row: "
                            + left.getMessage().getString()
                            + " / "
                            + right.getMessage().getString());
    }

    private static void verifyRemoteCache(ClientGameTestContext ctx) {
        var properties = new Properties();
        properties.setProperty("server-ip", "127.0.0.1");
        try (var server = ctx.worldBuilder().createServer(properties)) {
            server.runCommand(
                    "ahr add [\"Remote\"] Remote"
                        + " {\"primitives\":[{\"cylinder\":{\"center\":[0,0],\"y\":\"unbounded\",\"radiusSquared\":9}}],\"expression\":\"0\"}");
            try (var connection = server.connect()) {
                ctx.waitFor(
                        mc ->
                                mod().regions.ready
                                        && mod().regions
                                                .dimension("minecraft:overworld")
                                                .tree()
                                                .root
                                                .children
                                                .containsKey("Remote"),
                        1200);
                ctx.runOnClient(
                        mc -> {
                            var root =
                                    mc.gameDirectory
                                            .toPath()
                                            .resolve("cache/area-hints-reimagined/servers");
                            try (var entries = java.nio.file.Files.walk(root)) {
                                if (entries.noneMatch(
                                        p ->
                                                p.endsWith(
                                                        java.nio.file.Path.of(
                                                                "regions/minecraft/overworld.json"))))
                                    throw new AssertionError("Missing multiplayer cache");
                            } catch (java.io.IOException e) {
                                throw new AssertionError(e);
                            }
                        });
            }
            try (var connection = server.connect()) {
                ctx.waitFor(
                        mc ->
                                mod().regions.ready
                                        && mod().regions
                                                .dimension("minecraft:overworld")
                                                .tree()
                                                .root
                                                .children
                                                .containsKey("Remote"),
                        1200);
                server.runCommand("ahr del [\"Remote\"]");
                ctx.waitFor(
                        mc ->
                                !mod().regions
                                        .dimension("minecraft:overworld")
                                        .tree()
                                        .root
                                        .children
                                        .containsKey("Remote"));
            }
        }
    }

    static void click(ClientGameTestContext ctx, String label) {
        for (int i = 0; i < 8; i++) {
            if (ctx.tryClickScreenButton(label)) return;
            if (!ctx.tryClickScreenButton("›")) break;
        }
        throw new AssertionError("Missing button: " + label);
    }
}
