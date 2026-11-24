package io.github.imsng1019.ahr.data;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class Json {
    private Json() {}

    public static final Gson GSON =
            new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static JsonElement parse(String text) {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Trailing JSON input");
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static JsonElement read(JsonReader r) throws IOException {
        return switch (r.peek()) {
            case BEGIN_OBJECT -> {
                var obj = new JsonObject();
                r.beginObject();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (obj.has(key))
                        throw new IllegalArgumentException(
                                "Duplicate JSON member at " + r.getPath());
                    obj.add(key, read(r));
                }
                r.endObject();
                yield obj;
            }
            case BEGIN_ARRAY -> {
                var a = new JsonArray();
                r.beginArray();
                while (r.hasNext()) a.add(read(r));
                r.endArray();
                yield a;
            }
            default -> JsonParser.parseReader(r);
        };
    }

    public static JsonObject object(JsonObject obj, String key) {
        return obj.has(key) ? obj.getAsJsonObject(key) : new JsonObject();
    }

    public static void putOptionalObject(JsonObject obj, String key, JsonObject value) {
        if (value.isEmpty()) obj.remove(key);
        else obj.add(key, value);
    }

    public static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a JSON string");
        return value.getAsString();
    }

    public static boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected a JSON boolean");
        return value.getAsBoolean();
    }

    public static BigInteger integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected a JSON integer");
        return new BigInteger(value.getAsString());
    }

    public static int coord(JsonElement value) {
        return integer(value).intValueExact();
    }

    public static JsonArray tuple(JsonElement e, int size) {
        var a = e.getAsJsonArray();
        if (a.size() != size) throw new IllegalArgumentException("Expected " + size + " entries");
        return a;
    }

    public static JsonArray numbers(int... values) {
        var a = new JsonArray();
        for (int v : values) a.add(v);
        return a;
    }

    public static JsonArray path(List<String> segments) {
        var a = new JsonArray();
        segments.forEach(a::add);
        return a;
    }

    public static List<String> path(JsonElement json) {
        return json.getAsJsonArray().asList().stream().map(Json::string).toList();
    }

    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".ahr-", ".tmp");
        try {
            Files.writeString(temp, text);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
