package io.github.imsng1019.ahr.data;

import com.google.gson.*;

import io.github.imsng1019.ahr.geometry.RegionPredicate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class WorldFiles {
    public static final String DIRECTORY = "area-hints-reimagined";

    public record Dimension(String text, String digest, RegionTree tree) {
        public static Dimension read(String text, Dimension previous) {
            return new Dimension(
                    text,
                    Json.digest(text),
                    new RegionTree(
                            Json.parse(text).getAsJsonObject(),
                            previous == null ? null : previous.tree));
        }
    }

    public static final Dimension EMPTY = Dimension.read("{}", null);
    private final Path base;
    private Map<String, Dimension> dimensions = Map.of();
    private JsonObject settings = new JsonObject();
    private String settingsFileDigest;

    public WorldFiles(Path world) {
        base = world.resolve(DIRECTORY);
    }

    public Map<String, Dimension> dimensions() {
        return dimensions;
    }

    public JsonObject settings() {
        return settings;
    }

    public Dimension dimension(String id) {
        return dimensions.getOrDefault(id, EMPTY);
    }

    public static Path file(Path base, String id) {
        int split = id.indexOf(':');
        if (split < 1) throw new IllegalArgumentException("Invalid dimension " + id);
        String namespace = id.substring(0, split), path = id.substring(split + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || namespace.equals(".") || namespace.equals(".."))
            throw new IllegalArgumentException("Invalid dimension namespace");
        for (String part : path.split("/", -1))
            if (!part.matches("[a-z0-9_.-]+") || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Invalid dimension path");
        return base.resolve("regions").resolve(namespace).resolve(path + ".json");
    }

    public void reload() throws IOException {
        var next = new LinkedHashMap<String, Dimension>();
        Path regions = base.resolve("regions"), config = base.resolve("config.json");
        JsonObject nextSettings;
        String configText;
        try {
            configText = Files.notExists(config) ? null : Files.readString(config);
            nextSettings =
                    configText == null
                            ? new JsonObject()
                            : Json.parse(configText).getAsJsonObject();
            Presentation.validateHints(Json.object(nextSettings, "hints"));
        } catch (RuntimeException e) {
            throw new IOException(config + ": " + e.getMessage(), e);
        }
        if (Files.exists(regions)) {
            try (var files = Files.walk(regions)) {
                for (Path f :
                        files.filter(Files::isRegularFile)
                                .filter(p -> p.toString().endsWith(".json"))
                                .toList()) {
                    try {
                        Path rel = regions.relativize(f);
                        if (rel.getNameCount() < 2)
                            throw new IllegalArgumentException("Missing namespace");
                        String name =
                                rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
                        String id = rel.getName(0) + ":" + name.substring(0, name.length() - 5);
                        file(base, id);
                        next.put(id, Dimension.read(Files.readString(f), dimensions.get(id)));
                    } catch (RuntimeException e) {
                        throw new IOException(f + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        dimensions = Collections.unmodifiableMap(next);
        settings = nextSettings;
        settingsFileDigest = configText == null ? null : Json.digest(configText);
    }

    public void saveHints(String expected, JsonObject hints) throws IOException {
        if (!Json.digest(settings.toString()).equals(expected))
            throw new IOException("World hint settings changed. Reopen settings.");
        Path dest = base.resolve("config.json");
        String disk = Files.exists(dest) ? Json.digest(Files.readString(dest)) : null;
        if (!Objects.equals(disk, settingsFileDigest))
            throw new IOException("World settings changed on disk. Use /ahr reload.");
        Presentation.validateHints(hints);
        var next = settings.deepCopy();
        if (hints.isEmpty()) next.remove("hints");
        else next.add("hints", hints);
        String text = Json.GSON.toJson(next) + "\n";
        Json.write(dest, text);
        settings = next;
        settingsFileDigest = Json.digest(text);
    }

    public void edit(String id, String expectedDigest, Consumer<JsonObject> edit)
            throws IOException {
        var old = dimension(id);
        if (expectedDigest != null && !old.digest.equals(expectedDigest))
            throw new IllegalArgumentException(
                    "The dimension changed while editing. Reopen the region.");
        Path dest = file(base, id);
        boolean exists = Files.exists(dest);
        if (exists != dimensions.containsKey(id)
                || (exists && !old.digest.equals(Json.digest(Files.readString(dest)))))
            throw new IOException(
                    "The dimension file changed on disk. Use /ahr reload before editing.");
        JsonObject root = old.tree.root.json.deepCopy();
        edit.accept(root);
        String text = Json.GSON.toJson(root) + "\n";
        var next = Dimension.read(text, old);
        Json.write(dest, text);
        var copy = new LinkedHashMap<>(dimensions);
        copy.put(id, next);
        dimensions = Collections.unmodifiableMap(copy);
    }

    public static JsonObject node(JsonObject root, List<String> path) {
        var n = root;
        for (var id : path) {
            var children = Json.object(n, "children");
            if (!children.has(id))
                throw new IllegalArgumentException("Unknown region " + Json.path(path));
            n = children.getAsJsonObject(id);
        }
        return n;
    }

    private static List<String> parent(List<String> path) {
        if (path.isEmpty()) throw new IllegalArgumentException("Select a concrete region");
        return path.subList(0, path.size() - 1);
    }

    public static void delete(JsonObject root, List<String> path, boolean subtree) {
        var p = node(root, parent(path));
        var target = node(root, path);
        if (!subtree && !Json.object(target, "children").isEmpty())
            throw new IllegalArgumentException("Use subtree to delete descendants");
        var children = p.getAsJsonObject("children");
        children.remove(path.getLast());
        if (children.isEmpty()) p.remove("children");
    }

    public static void put(
            JsonObject root, List<String> original, List<String> dest, JsonObject fields) {
        if (original != null && original.isEmpty() && dest.isEmpty()) {
            copyFields(root, fields);
            return;
        }
        var p = node(root, parent(dest));
        JsonObject entry = original == null ? new JsonObject() : node(root, original);
        boolean moving = original != null && !original.equals(dest);
        if (moving
                && dest.size() > original.size()
                && dest.subList(0, original.size()).equals(original))
            throw new IllegalArgumentException("Cannot move a region into its descendant");
        var children = Json.object(p, "children");
        if ((original == null || moving) && children.has(dest.getLast()))
            throw new IllegalArgumentException("Duplicate sibling identifier");
        var predicate = new RegionPredicate(fields.getAsJsonObject("predicate"), true);
        if (moving) delete(root, original, true);
        copyFields(entry, fields);
        entry.add("predicate", fields.get("predicate"));
        entry.add("aabb", predicate.bounds().json());
        p.add("children", children);
        children.add(dest.getLast(), entry);
    }

    private static void copyFields(JsonObject to, JsonObject from) {
        for (String key : List.of("name", "hint")) {
            if (from.has(key)) to.add(key, from.get(key));
            else to.remove(key);
        }
    }

    public static void recalculate(JsonObject root, List<String> path, boolean subtree) {
        if (path.isEmpty() && !subtree)
            throw new IllegalArgumentException("The root has no own AABB");
        recalculate(node(root, path), path.isEmpty(), subtree);
    }

    private static void recalculate(JsonObject node, boolean root, boolean subtree) {
        if (!root)
            node.add(
                    "aabb",
                    new RegionPredicate(node.getAsJsonObject("predicate"), false).bounds().json());
        if (subtree)
            Json.object(node, "children")
                    .entrySet()
                    .forEach(e -> recalculate(e.getValue().getAsJsonObject(), false, true));
    }
}
