package io.github.imsng1019.ahr.data;

import static org.junit.Assert.*;

import com.google.gson.*;

import io.github.imsng1019.ahr.geometry.Point;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class WorldTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String ID = "minecraft:overworld";

    private static JsonObject region(int x, int r) {
        return Json.parse(
                        """
                        {"name":{"fallback":""},"predicate":{"primitives":[{"sphere":{"center":[%d,0,0],"radiusSquared":%d}}],
                        "expression":"0"}}
                        """
                                .formatted(x, r * r))
                .getAsJsonObject();
    }

    @Test
    public void transactionsKeepOldStateAndPreserveOrder() throws IOException {
        Path dir = folder.getRoot().toPath();
        var w = new WorldFiles(dir);
        w.reload();
        w.edit(ID, null, r -> WorldFiles.put(r, null, List.of("A"), region(0, 1)));
        w.edit(ID, null, r -> WorldFiles.put(r, null, List.of("B"), region(0, 1)));
        w.edit(ID, null, r -> WorldFiles.put(r, null, List.of("A", "A"), region(25, 0)));
        var tree = w.dimension(ID).tree();
        assertEquals(List.of("A", "A"), tree.matches(new Point(25, 0, 0)).getFirst().path);
        assertFalse(tree.root.children.get("A").own.contains(new Point(25, 0, 0)));
        String digest = w.dimension(ID).digest();
        assertThrows(
                IllegalArgumentException.class,
                () -> w.edit(ID, digest, r -> WorldFiles.delete(r, List.of("A"), false)));
        w.edit(ID, digest, r -> WorldFiles.put(r, List.of("A"), List.of("A"), region(0, 2)));
        assertThrows(
                IllegalArgumentException.class,
                () -> w.edit(ID, digest, r -> WorldFiles.delete(r, List.of("B"), true)));
        assertEquals(List.of("A", "B"), List.copyOf(w.dimension(ID).tree().root.children.keySet()));
        w.edit(ID, null, r -> WorldFiles.delete(r, List.of("A", "A"), false));
        assertEquals(2, w.dimension(ID).tree().root.subtree.x().high());
        var f = WorldFiles.file(dir.resolve(WorldFiles.DIRECTORY), ID);
        String good = Files.readString(f);
        Files.writeString(f, "{");
        assertThrows(IOException.class, w::reload);
        assertThrows(
                IOException.class,
                () -> w.edit(ID, null, r -> WorldFiles.delete(r, List.of("A"), true)));
        assertEquals(2, w.dimension(ID).tree().root.children.size());
        Files.writeString(f, good);
        w.reload();
        Files.delete(f);
        w.reload();
        assertTrue(w.dimensions().isEmpty());
    }

    @Test
    public void detectionUsesAllEligibleMatchesAndWrittenOrder() {
        var root = new JsonObject();
        WorldFiles.put(root, null, List.of("A"), region(0, 10));
        WorldFiles.put(root, null, List.of("E"), region(8, 1));
        WorldFiles.put(root, null, List.of("A", "C"), region(80, 1));
        WorldFiles.put(root, null, List.of("A", "C", "B"), region(0, 1));
        var t = new RegionTree(root);
        var d = new Detection();
        assertEquals(List.of("A", "C", "B"), d.sample(t, new Point(0, 0, 0), true).path);
        assertEquals(List.of("A"), d.sample(t, new Point(8, 0, 0), true).path);
        assertNull(d.sample(t, new Point(8, 0, 0), true));
        assertEquals(List.of(), d.sample(t, new Point(40, 0, 0), true).path);
        var mute = new JsonObject();
        mute.addProperty("enabled", false);
        WorldFiles.node(root, List.of("A", "C", "B")).add("hint", mute);
        t = new RegionTree(root);
        d.reset();
        assertEquals(List.of("A"), d.sample(t, new Point(0, 0, 0), true).path);
        assertEquals(List.of("E"), d.sample(t, new Point(8, 0, 0), true).path);
    }

    @Test
    public void sliderKeepsFractionalGameTicks() {
        var d = new Detection();
        var samples = new ArrayList<Integer>();
        for (int i = 1; i <= 10; i++) if (d.advance(.0625)) samples.add(i);
        assertEquals(List.of(2, 3, 4, 5, 7, 8, 9, 10), samples);
        for (int i = 0; i <= 256; i++)
            assertEquals(i / 256.0, Detection.position(Detection.seconds(i / 256.0)), 1e-12);
        assertEquals(1, Detection.seconds(.5), 0);
    }

    @Test
    public void strictJsonAndDimensionFilePaths() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":{},\"a\":{}}"));
        assertThrows(RuntimeException.class, () -> Json.parse("{\"x\":1}{}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldFiles.file(Path.of("world"), "abc:../outside"));
        assertTrue(
                WorldFiles.file(Path.of("world"), "abc:foo/bar")
                        .endsWith(Path.of("regions/abc/foo/bar.json")));
    }

    @Test
    public void worldHintEditsPreserveMissingAndExplicitEmptySettings() throws IOException {
        var world = new WorldFiles(folder.getRoot().toPath());
        world.reload();
        String before = Json.digest(world.settings().toString());
        var hints = Json.parse("{\"showFullPath\":false,\"separator\":\"\"}").getAsJsonObject();
        world.saveHints(before, hints);
        assertThrows(IOException.class, () -> world.saveHints(before, new JsonObject()));
        world.reload();
        assertFalse(world.settings().getAsJsonObject("hints").has("useRichText"));
        assertEquals("", world.settings().getAsJsonObject("hints").get("separator").getAsString());
        world.saveHints(Json.digest(world.settings().toString()), new JsonObject());
        assertFalse(world.settings().has("hints"));
    }
}
