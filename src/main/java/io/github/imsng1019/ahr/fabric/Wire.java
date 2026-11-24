package io.github.imsng1019.ahr.fabric;

import com.google.gson.JsonObject;

import io.github.imsng1019.ahr.data.Json;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

public final class Wire {
    private Wire() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("area_hints_reimagined", path);
    }

    // Fabric handles fragmentation on the wire. A JSON document can span any
    // number of these bounded logical packets on the ordered play connection.
    private static final int CHARS = 256 * 1024;
    private static final int MAX_BYTES = 3 * CHARS + 8;

    public record Part(boolean last, String text) {
        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeBoolean(last);
            buf.writeUtf(text, CHARS);
        }

        private static Part read(RegistryFriendlyByteBuf buf) {
            return new Part(buf.readBoolean(), buf.readUtf(CHARS));
        }
    }

    public static void parts(String text, Consumer<Part> send) {
        int start = 0;
        while (text.length() - start > CHARS) {
            int end = start + CHARS;
            if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            send.accept(new Part(false, text.substring(start, end)));
            start = end;
        }
        send.accept(new Part(true, text.substring(start)));
    }

    public static final class Incoming {
        private StringBuilder partial;

        public String accept(Part part) {
            if (partial == null && part.last) return part.text;
            if (partial == null) partial = new StringBuilder();
            partial.append(part.text);
            if (!part.last) return null;
            String complete = partial.toString();
            partial = null;
            return complete;
        }

        public void clear() {
            partial = null;
        }
    }

    public record ToClient(Part part) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(id("sync"));

        public Type<ToClient> type() {
            return TYPE;
        }
    }

    public record ToServer(Part part) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(id("request"));

        public Type<ToServer> type() {
            return TYPE;
        }
    }

    public static void register() {
        StreamCodec<RegistryFriendlyByteBuf, ToClient> down =
                StreamCodec.of((buf, p) -> p.part.write(buf), buf -> new ToClient(Part.read(buf)));
        StreamCodec<RegistryFriendlyByteBuf, ToServer> up =
                StreamCodec.of((buf, p) -> p.part.write(buf), buf -> new ToServer(Part.read(buf)));
        PayloadTypeRegistry.clientboundPlay().registerLarge(ToClient.TYPE, down, MAX_BYTES);
        PayloadTypeRegistry.serverboundPlay().registerLarge(ToServer.TYPE, up, MAX_BYTES);
    }

    public static JsonObject message(String kind) {
        var o = new JsonObject();
        o.addProperty("kind", kind);
        return o;
    }

    public static String field(JsonObject o, String key) {
        return Json.string(o.get(key));
    }
}
