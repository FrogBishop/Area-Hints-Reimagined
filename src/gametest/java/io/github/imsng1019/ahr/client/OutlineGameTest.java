package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.RegionGameTest.click;
import static io.github.imsng1019.ahr.client.RegionGameTest.mod;

import io.github.imsng1019.ahr.geometry.Point;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class OutlineGameTest implements FabricClientGameTest {
    private static final String GALLERY =
            """
            {"primitives":[
              {"sphere":{"center":[-7,178,14],"radiusSquared":4}},
              {"cylinder":{"center":[-2,14],"radiusSquared":3,"y":[176,180]}},
              {"prism":{"vertices":[[1,12],[4,12],[4,16],[1,16]],"insideAtInfinity":false,"y":[176,180]}},
              {"polyhedron":{"vertices":[[6,176,12],[10,176,12],[8,176,16],[8,181,14]],
              "faces":[[0,1,2],[0,1,3],[0,2,3],[1,2,3]],"insideAtInfinity":false}}],"expression":"0"}
            """
                    .replace("\n", "");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (var world = ctx.worldBuilder().create()) {
            ctx.waitFor(mc -> mc.player != null && mod().regions.ready, 1200);
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a 0.5 183 -2 0 17");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("ahr add [\"Gallery\"] Gallery " + GALLERY);
            ctx.waitFor(
                    mc ->
                            mc.player.getY() == 183
                                    && mod().regions
                                            .dimension(AreaHintsClient.dimension())
                                            .tree()
                                            .root
                                            .children
                                            .containsKey("Gallery"));
            ctx.runOnClient(mc -> mc.getConnection().sendCommand("ahr hints off"));
            ctx.waitFor(mc -> !mod().settings.enabled());
            ctx.getInput().pressKey(options -> options.keyToggleGui);
            ctx.waitTicks(6);
            if (ctx.computeOnClient(mc -> mod().settings.showOutlines()))
                throw new AssertionError("Outlines should default to off");
            world.getConnection().waitForChunksRender();
            var off = ctx.takeScreenshot("primitive-outlines-off");
            toggle(ctx, false);
            ctx.waitTicks(3);
            var on = ctx.takeScreenshot("primitive-outlines-saved");

            ctx.runOnClient(mc -> mod().editor.open());
            click(ctx, "New Region");
            click(ctx, "Add Sphere");
            ctx.runOnClient(
                    mc -> {
                        mod().editor.mark(new Point(0, 183, 14));
                        mod().editor.mark(new Point(2, 183, 14));
                    });
            ctx.waitTicks(3);
            var draft = ctx.takeScreenshot("primitive-outlines-editing");
            verifyMore(off, on, WorldOutlines.SAVED);
            verifyMore(on, draft, WorldOutlines.DRAFT);

            ctx.runOnClient(mc -> mod().editor.open());
            click(ctx, "Discard changes");
            click(ctx, "Discard changes");
            click(ctx, "Discard changes");
            click(ctx, "Discard changes");
            ctx.runOnClient(mc -> mc.gui.setScreen(null));
            toggle(ctx, true);
            ctx.waitTicks(3);
            verifyMore(ctx.takeScreenshot("primitive-outlines-hidden"), on, WorldOutlines.SAVED);
            world.getServer().runCommand("fill -4 179 7 0 181 7 minecraft:bricks");
            ctx.waitFor(mc -> mc.level.getBlockState(new BlockPos(-2, 180, 7)).is(Blocks.BRICKS));
            world.getConnection().waitForChunksRender();
            var wallOff = waitForWall(ctx);
            toggle(ctx, false);
            ctx.waitTicks(3);
            var wallOn = ctx.takeScreenshot("primitive-surfaces-wall");
            verifyOcclusion(off, on, wallOff, wallOn);
            toggle(ctx, true);
            ctx.getInput().pressKey(options -> options.keyToggleGui);
            ctx.runOnClient(mc -> mc.getConnection().sendCommand("ahr hints on"));
            ctx.waitFor(mc -> mod().settings.enabled());
        }
    }

    private static Path waitForWall(ClientGameTestContext ctx) {
        // The server block update can reach the client before its rebuilt terrain
        // mesh is on the GPU. Capture the baseline only after the bricks are visible.
        var options =
                TestScreenshotOptions.of("primitive-surfaces-wall-off").disableCounterPrefix();
        for (int i = 0; i < 40; i++) {
            ctx.waitTicks(2);
            Path path = ctx.takeScreenshot(options);
            try {
                var image = ImageIO.read(path.toFile());
                int bricks = 0;
                for (int y = image.getHeight() / 2; y < image.getHeight() * 2 / 3; y += 4)
                    for (int x = image.getWidth() / 2; x < image.getWidth() * 2 / 3; x += 4)
                        if (isBrick(image.getRGB(x, y))) bricks++;
                if (bricks > 80) return path;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("The test wall never appeared in the terrain mesh");
    }

    private static boolean isBrick(int rgb) {
        return ((rgb >> 16) & 255) > (rgb & 255) + 20;
    }

    private static void verifyOcclusion(Path bare, Path filled, Path wall, Path drawn) {
        try {
            BufferedImage a = ImageIO.read(bare.toFile()), b = ImageIO.read(filled.toFile());
            BufferedImage c = ImageIO.read(wall.toFile()), d = ImageIO.read(drawn.toFile());
            int hidden = 0, leaked = 0, exposed = 0, linesThroughWall = 0;
            for (int y = 0; y < a.getHeight(); y++)
                for (int x = 0; x < a.getWidth(); x++) {
                    int sky = a.getRGB(x, y),
                            overlay = b.getRGB(x, y),
                            brick = c.getRGB(x, y),
                            result = d.getRGB(x, y);
                    boolean isBrick = isBrick(brick) && contrast(sky, brick) > 30;
                    if (contrast(overlay, WorldOutlines.SAVED) < 28) {
                        if (isBrick && contrast(result, WorldOutlines.SAVED) < 28)
                            linesThroughWall++;
                    } else if (contrast(sky, overlay) > 20) {
                        if (isBrick) {
                            hidden++;
                            if (contrast(brick, result) > 12) leaked++;
                        } else if (contrast(sky, brick) < 12 && contrast(brick, result) > 20)
                            exposed++;
                    }
                }
            if (hidden < 100 || leaked > hidden / 20 || exposed < 100 || linesThroughWall < 40)
                throw new AssertionError(
                        "Expected terrain-occluded faces and visible outlines. Covered pixels: "
                                + hidden
                                + ", leaking: "
                                + leaked
                                + ", exposed: "
                                + exposed
                                + ", lines on brick: "
                                + linesThroughWall);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static int contrast(int a, int b) {
        int r = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255));
        int g = Math.abs(((a >> 8) & 255) - ((b >> 8) & 255));
        return Math.max(Math.max(r, g), Math.abs((a & 255) - (b & 255)));
    }

    private static void toggle(ClientGameTestContext ctx, boolean previous) {
        ctx.runOnClient(mc -> mod().editor.open());
        click(ctx, "Global Settings");
        String label =
                ctx.computeOnClient(
                        mc ->
                                MenuPage.tr("showPrimitiveOutlines").getString()
                                        + "  "
                                        + Component.translatable(
                                                        previous ? "options.on" : "options.off")
                                                .getString());
        click(ctx, label);
        ctx.clickScreenButton(Component.translatable("gui.back").getString());
        ctx.runOnClient(
                mc -> {
                    if (new ClientSettings().showOutlines() == previous)
                        throw new AssertionError("Outline setting did not persist");
                    mc.gui.setScreen(null);
                });
    }

    private static void verifyMore(Path before, Path after, int color) {
        int baseline = pixels(before, color), actual = pixels(after, color);
        if (actual < baseline + 40)
            throw new AssertionError(
                    "Expected outline pixels in "
                            + after
                            + " for "
                            + Integer.toHexString(color)
                            + ", found "
                            + actual
                            + " vs "
                            + baseline);
    }

    private static int pixels(Path file, int color) {
        try {
            var image = ImageIO.read(file.toFile());
            int count = 0;
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    if (Math.abs(((rgb >> 16) & 255) - ((color >> 16) & 255)) < 20
                            && Math.abs(((rgb >> 8) & 255) - ((color >> 8) & 255)) < 20
                            && Math.abs((rgb & 255) - (color & 255)) < 20) count++;
                }
            return count;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
