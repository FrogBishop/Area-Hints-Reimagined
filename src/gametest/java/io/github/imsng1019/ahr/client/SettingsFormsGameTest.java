package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.MenuPage.tr;
import static io.github.imsng1019.ahr.client.RegionGameTest.mod;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.AreaHints;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

public final class SettingsFormsGameTest implements FabricClientGameTest {
    private static final String DIM = "minecraft:overworld";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (var world = ctx.worldBuilder().create()) {
            ctx.waitFor(mc -> mc.player != null && mod().regions.ready, 1200);
            String originalLanguage =
                    ctx.computeOnClient(mc -> mc.getLanguageManager().getSelected());
            var originalConfig = ctx.computeOnClient(mc -> mod().settings.copy());
            try {
                RegionGameTest.setLanguage(ctx, "zh_cn");
                global(ctx);
                worldSettings(ctx, world);
                for (String id : List.of("Town", "Camp"))
                    world.getServer()
                            .runCommand(
                                    "ahr add [\""
                                            + id
                                            + "\"] "
                                            + id
                                            + " {\"primitives\":[{\"sphere\":{\"center\":[0,64,0],\"radiusSquared\":4}}],\"expression\":\"0\"}");
                ctx.waitFor(
                        mc ->
                                mod().regions.ready
                                        && mod().regions
                                                .dimension(DIM)
                                                .tree()
                                                .root
                                                .children
                                                .containsKey("Camp"));
                region(ctx, world);
            } finally {
                ctx.runOnClient(
                        mc -> {
                            mod().settings.save(originalConfig);
                            mod().editor.reset();
                        });
                RegionGameTest.setLanguage(ctx, originalLanguage);
                ctx.runOnClient(mc -> mc.gui.setScreen(null));
            }
        }
    }

    private static void global(ClientGameTestContext ctx) {
        button(ctx, "settings");
        field(ctx, "positionX", "1.1");
        field(ctx, "positionY", "0.75");
        back(ctx);
        expectTitle(ctx, "settings");
        field(ctx, "positionX", "0.25");
        animation(ctx, "Wipe");
        button(ctx, "animationParameters");
        field(ctx, "param.holdMs", "1250");
        click(ctx, s -> s.startsWith(tr("wipeOrigin", "").getString().strip()));
        click(ctx, s -> s.startsWith(tr("wipeOrigin", "").getString().strip()));
        back(ctx);
        seek(ctx, tr("positionX").getString()::equals);
        ctx.takeScreenshot(TestScreenshotOptions.of("hint-position-zh").disableCounterPrefix());
        ctx.runOnClient(mc -> mod().enable(false));
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ctx.waitFor(
                mc ->
                        mc.gui.screen() != null
                                && mc.gui
                                        .screen()
                                        .getTitle()
                                        .getString()
                                        .equals(tr("menu").getString()));
        ctx.runOnClient(
                mc -> {
                    var saved = new ClientSettings().hints();
                    if (mod().settings.enabled()
                            || !saved.get("position").equals(Json.parse("[0.25,0.75]"))
                            || !saved.get("animation")
                                    .equals(
                                            Json.parse(
                                                    "{\"type\":\"Wipe\",\"holdMs\":1250,\"origin\":\"right\"}")))
                        throw new AssertionError("Global settings did not round trip: " + saved);
                });
    }

    private static void worldSettings(ClientGameTestContext ctx, TestSingleplayerContext world) {
        button(ctx, "worldSettings");
        click(ctx, s -> s.startsWith(tr("showFullPath").getString()));
        back(ctx);
        ctx.waitFor(
                mc ->
                        Json.object(mod().regions.settings, "hints").has("showFullPath")
                                && mc.gui.screen() != null
                                && mc.gui
                                        .screen()
                                        .getTitle()
                                        .getString()
                                        .equals(tr("menu").getString()));
        world.getServer()
                .runOnServer(
                        s -> {
                            var hints = Json.object(AreaHints.world(s).settings(), "hints");
                            if (!hints.equals(Json.parse("{\"showFullPath\":true}")))
                                throw new AssertionError("Unexpected world overrides " + hints);
                        });

        button(ctx, "worldSettings");
        click(ctx, s -> s.startsWith(tr("useRichText").getString()));
        world.getServer()
                .runOnServer(
                        s -> {
                            var files = AreaHints.world(s);
                            try {
                                files.saveHints(
                                        Json.digest(files.settings().toString()),
                                        Json.parse("{\"separator\":\"|\"}").getAsJsonObject());
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                            AreaHints.publish(s);
                        });
        ctx.waitFor(mc -> Json.object(mod().regions.settings, "hints").has("separator"));
        back(ctx);
        ctx.waitFor(mc -> find(mc, tr("discard").getString()::equals) != null);
        button(ctx, "discard");
        expectTitle(ctx, "menu");
        world.getServer()
                .runOnServer(
                        s -> {
                            if (!Json.object(AreaHints.world(s).settings(), "hints")
                                    .equals(Json.parse("{\"separator\":\"|\"}")))
                                throw new AssertionError(
                                        "The stale form overwrote the concurrent edit");
                        });
    }

    private static void region(ClientGameTestContext ctx, TestSingleplayerContext world) {
        button(ctx, "editRegion");
        click(ctx, s -> s.startsWith("Camp  "));
        button(ctx, "select");
        button(ctx, "properties");
        field(ctx, "fallback", "Camp");
        field(ctx, "parent", "[\"Camp\"]");
        back(ctx);
        expectTitle(ctx, "properties");
        button(ctx, "browse");
        if (ctx.computeOnClient(mc -> find(mc, s -> s.startsWith("Camp  ")) != null))
            throw new AssertionError("Region offered itself as a parent");
        click(ctx, s -> s.startsWith("Town  "));
        button(ctx, "chooseParent");
        addLanguage(ctx, "en_us", "Camp");
        addLanguage(ctx, "zh_cn", "营地");
        addLanguage(ctx, "fr_fr", "Campement");
        exact(ctx, tr("removeLanguage", "fr_fr").getString());
        button(ctx, "richText");
        addLanguage(ctx, "zh_cn", "{\"text\":\"营地\",\"color\":\"gold\"}");
        back(ctx);
        field(ctx, "positionY", "0");
        animation(ctx, "Instant");
        button(ctx, "animationParameters");
        field(ctx, "param.holdMs", "0");
        back(ctx);
        // Return to the first page so the parent browser and translation rows are visible together.
        seek(ctx, tr("browse").getString()::equals);
        ctx.takeScreenshot(TestScreenshotOptions.of("region-properties-zh").disableCounterPrefix());
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        expectTitle(ctx, "editRegion");
        world.getServer()
                .runOnServer(
                        s -> {
                            if (!AreaHints.world(s)
                                    .dimension(DIM)
                                    .tree()
                                    .root
                                    .children
                                    .containsKey("Camp"))
                                throw new AssertionError(
                                        "Leaving properties committed the region early");
                        });
        button(ctx, "properties");
        String parent = fieldValue(ctx, "parent");
        if (!parent.equals("[\"Town\"]"))
            throw new AssertionError("Parent lost on reopening: " + parent);
        back(ctx);
        button(ctx, "save");
        ctx.waitFor(
                mc ->
                        mod().regions.ready
                                && mod().regions
                                        .dimension(DIM)
                                        .tree()
                                        .find(List.of("Town"))
                                        .children
                                        .containsKey("Camp"));
        world.getServer()
                .runOnServer(
                        s -> {
                            var n =
                                    AreaHints.world(s)
                                            .dimension(DIM)
                                            .tree()
                                            .find(List.of("Town", "Camp"))
                                            .json;
                            var hints = n.getAsJsonObject("hint");
                            if (!n.getAsJsonObject("name")
                                            .get("translations")
                                            .equals(
                                                    Json.parse(
                                                            "{\"en_us\":\"Camp\",\"zh_cn\":\"营地\"}"))
                                    || !hints.getAsJsonObject("richText")
                                            .get("translations")
                                            .equals(
                                                    Json.parse(
                                                            "{\"zh_cn\":{\"text\":\"营地\",\"color\":\"gold\"}}"))
                                    || !hints.get("position").equals(Json.parse("[0.25,0]"))
                                    || !hints.get("animation")
                                            .equals(
                                                    Json.parse(
                                                            "{\"type\":\"Instant\",\"holdMs\":0}")))
                                throw new AssertionError(
                                        "Region property edits did not round trip: " + n);
                        });
    }

    private static void animation(ClientGameTestContext ctx, String type) {
        click(ctx, s -> s.startsWith(tr("chooseAnimation", "").getString().strip()));
        if (type.equals("Instant"))
            ctx.takeScreenshot(
                    TestScreenshotOptions.of("animation-picker-zh").disableCounterPrefix());
        button(ctx, "effect." + type);
    }

    private static void addLanguage(ClientGameTestContext ctx, String code, String value) {
        button(ctx, "addLanguage");
        field(ctx, "languageSearch", code);
        button(ctx, "filterLanguages");
        click(ctx, s -> s.endsWith(" · " + code));
        ctx.runOnClient(
                mc -> {
                    var field = (EditBox) find(mc, s -> s.endsWith(" · " + code));
                    if (field == null)
                        throw new AssertionError("New language row not visible: " + code);
                    field.setValue(value);
                });
    }

    private static void field(ClientGameTestContext ctx, String key, String value) {
        String label = tr(key).getString();
        seek(ctx, label::equals);
        ctx.runOnClient(mc -> ((EditBox) find(mc, label::equals)).setValue(value));
    }

    private static String fieldValue(ClientGameTestContext ctx, String key) {
        String label = tr(key).getString();
        seek(ctx, label::equals);
        return ctx.computeOnClient(mc -> ((EditBox) find(mc, label::equals)).getValue());
    }

    private static void button(ClientGameTestContext ctx, String key) {
        exact(ctx, tr(key).getString());
    }

    private static void exact(ClientGameTestContext ctx, String label) {
        click(ctx, label::equals);
    }

    private static void back(ClientGameTestContext ctx) {
        ctx.clickScreenButton(Component.translatable("gui.back").getString());
    }

    private static void expectTitle(ClientGameTestContext ctx, String key) {
        ctx.runOnClient(
                mc -> {
                    if (!mc.gui.screen().getTitle().getString().equals(tr(key).getString()))
                        throw new AssertionError(
                                "Expected "
                                        + key
                                        + ", found "
                                        + mc.gui.screen().getTitle().getString());
                });
    }

    private static void click(ClientGameTestContext ctx, Predicate<String> wanted) {
        ctx.clickScreenButton(seek(ctx, wanted));
    }

    private static String seek(ClientGameTestContext ctx, Predicate<String> wanted) {
        if (ctx.computeOnClient(mc -> find(mc, wanted)) == null) {
            while (ctx.computeOnClient(mc -> find(mc, "‹"::equals) != null))
                ctx.clickScreenButton("‹");
            while (ctx.computeOnClient(
                    mc -> find(mc, wanted) == null && find(mc, "›"::equals) != null))
                ctx.clickScreenButton("›");
        }
        return ctx.computeOnClient(
                mc -> {
                    var widget = find(mc, wanted);
                    if (widget == null)
                        throw new AssertionError(
                                "Control missing on " + mc.gui.screen().getTitle().getString());
                    return widget.getMessage().getString();
                });
    }

    private static AbstractWidget find(Minecraft mc, Predicate<String> wanted) {
        if (mc.gui.screen() == null) return null;
        return mc.gui.screen().children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(w -> w.active && wanted.test(w.getMessage().getString()))
                .findFirst()
                .orElse(null);
    }
}
