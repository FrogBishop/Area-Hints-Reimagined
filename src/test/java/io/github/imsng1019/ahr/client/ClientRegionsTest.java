package io.github.imsng1019.ahr.client;

import static org.junit.Assert.*;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.Wire;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.util.*;

public class ClientRegionsTest {
    @Test
    public void chunkedDocumentsPreserveUnicodeAcrossPacketBoundaries() {
        String text = "x".repeat(256 * 1024 - 1) + "🧭" + "界".repeat(300_000);
        var parts = new ArrayList<Wire.Part>();
        Wire.parts(text, parts::add);
        assertTrue(parts.size() > 1);
        var incoming = new Wire.Incoming();
        for (int i = 0; i < parts.size() - 1; i++) {
            assertFalse(parts.get(i).last());
            assertFalse(
                    Character.isHighSurrogate(
                            parts.get(i).text().charAt(parts.get(i).text().length() - 1)));
            assertNull(incoming.accept(parts.get(i)));
        }
        assertEquals(text, incoming.accept(parts.getLast()));
        assertEquals("{}", incoming.accept(new Wire.Part(true, "{}")));
    }

    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final String ID = "minecraft:overworld";

    private static JsonObject manifest(String text) {
        var o = Wire.message("manifest");
        var hashes = new JsonObject();
        if (text != null) hashes.addProperty(ID, Json.digest(text));
        o.add("hashes", hashes);
        o.add("settings", new JsonObject());
        o.addProperty("author", false);
        return o;
    }

    private static JsonObject payload(String text) {
        var o = Wire.message("file");
        o.addProperty("dimension", ID);
        o.addProperty("digest", Json.digest(text));
        o.addProperty("text", text);
        return o;
    }

    @Test
    public void reconnectUsesOnlyConfirmedBytesAndDropsRemovedDimensions() throws Exception {
        Path cache = folder.getRoot().toPath();
        var client = new ClientRegions();
        var requested = new ArrayList<JsonObject>();
        String first = "{\"name\":{\"fallback\":\"old\"}}",
                next = "{\"name\":{\"fallback\":\"new\"}}";
        client.manifest(manifest(first), cache, requested::add);
        assertFalse(client.ready);
        assertEquals(Json.digest(first), requested.getFirst().get("digest").getAsString());
        client.file(payload(first));
        assertTrue(client.ready);
        assertEquals(first, Files.readString(WorldFiles.file(cache, ID)));
        client.reset();
        requested.clear();
        client.manifest(manifest(first), cache, requested::add);
        assertTrue(client.ready);
        assertTrue(requested.isEmpty());
        client.manifest(manifest(next), cache, requested::add);
        client.file(payload(first));
        assertFalse(client.ready);
        client.file(payload(next));
        assertEquals("new", client.dimension(ID).tree().root.name("en_us", ""));
        client.manifest(manifest(null), cache, requested::add);
        assertTrue(client.ready);
        assertTrue(client.active.isEmpty());
        client.reset();
        Files.writeString(WorldFiles.file(cache, ID), "damaged cache");
        requested.clear();
        client.manifest(manifest(next), cache, requested::add);
        assertFalse(client.ready);
        assertEquals(1, requested.size());
    }
}
