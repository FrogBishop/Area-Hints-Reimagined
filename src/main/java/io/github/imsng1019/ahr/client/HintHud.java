package io.github.imsng1019.ahr.client;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import io.github.imsng1019.ahr.data.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;

import java.util.*;
import java.util.regex.Pattern;

final class HintHud {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private record Line(FormattedCharSequence text, int width, List<Integer> ends) {}

    private List<Line> lines = List.of();
    private String type, origin;
    private double enter, hold, exit, offset, fromScale, x, y;
    private long start;
    private int width, graphemes;

    static String dimensionName(Minecraft mc) {
        var id = mc.level.dimension().identifier();
        String key = "dimension." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        key =
                switch (id.toString()) {
                    case "minecraft:overworld" -> "flat_world_preset.minecraft.overworld";
                    case "minecraft:the_nether" -> "advancements.nether.root.title";
                    case "minecraft:the_end" -> "biome.minecraft.the_end";
                    default -> key;
                };
        return Component.translatableWithFallback(key, id.toString()).getString();
    }

    static String plain(Minecraft mc, RegionTree.Node node) {
        return node.name(mc.getLanguageManager().getSelected(), dimensionName(mc));
    }

    static Component compose(
            Minecraft mc, RegionTree.Node node, ClientSettings local, JsonObject world) {
        Component target = Component.literal(plain(mc, node));
        if (local.setting(world, "useRichText", new JsonPrimitive(true)).getAsBoolean()) {
            var rich = Json.object(Json.object(node.json, "hint"), "richText");
            var values = Json.object(rich, "translations");
            String lang = mc.getLanguageManager().getSelected();
            var json = values.has(lang) ? values.get(lang) : rich.get("fallback");
            if (json != null)
                target =
                        ComponentSerialization.CODEC
                                .parse(
                                        mc.level
                                                .registryAccess()
                                                .createSerializationContext(JsonOps.INSTANCE),
                                        json)
                                .getOrThrow();
        }
        if (!local.setting(world, "showFullPath", new JsonPrimitive(false)).getAsBoolean())
            return target;
        String separator = local.setting(world, "separator", new JsonPrimitive("·")).getAsString();
        var ancestors = new ArrayDeque<RegionTree.Node>();
        for (var n = node.parent; n != null; n = n.parent) ancestors.push(n);
        var text = Component.empty();
        for (var n : ancestors)
            text.append(Component.literal(plain(mc, n))).append(Component.literal(separator));
        return text.append(target);
    }

    void show(Minecraft mc, RegionTree.Node node, ClientSettings local, JsonObject world) {
        Component value = compose(mc, node, local, world);
        var hint = Json.object(node.json, "hint");
        var global = local.hints();
        var position = hint.has("position") ? hint.get("position") : global.get("position");
        x = position == null ? 0.5 : position.getAsJsonArray().get(0).getAsDouble();
        y = position == null ? 0.25 : position.getAsJsonArray().get(1).getAsDouble();
        var motion = Presentation.animation(hint, global);
        type = motion.get("type").getAsString();
        origin = motion.has("origin") ? motion.get("origin").getAsString() : "left";
        enter = Presentation.number(motion, "enterMs", 400);
        hold = Presentation.number(motion, "holdMs", 1800);
        exit = Presentation.number(motion, "exitMs", 400);
        offset = Presentation.number(motion, "offsetLines", 0.75);
        fromScale = Presentation.number(motion, "fromScale", 0.90);
        width = 0;
        graphemes = 0;
        var layout = new ArrayList<Line>();
        for (var row : mc.font.split(value, Integer.MAX_VALUE)) {
            StringBuilder str = new StringBuilder();
            row.accept(
                    (i, style, cp) -> {
                        str.appendCodePoint(cp);
                        return true;
                    });
            var matcher = GRAPHEME.matcher(str);
            var ends = new ArrayList<Integer>();
            int codepoints = 0;
            while (matcher.find()) {
                codepoints += str.codePointCount(matcher.start(), matcher.end());
                ends.add(codepoints);
            }
            int w = mc.font.width(row);
            width = Math.max(width, w);
            graphemes += ends.size();
            layout.add(new Line(row, w, ends));
        }
        lines = layout;
        if (type.equals("Typewriter"))
            enter = 1000.0 * graphemes / Presentation.number(motion, "graphemesPerSecond", 14);
        if (type.equals("Instant")) {
            enter = 0;
            exit = 0;
        }
        start = System.nanoTime();
    }

    void clear() {
        lines = List.of();
    }

    void render(GuiGraphicsExtractor gui) {
        if (lines.isEmpty()) return;
        var mc = Minecraft.getInstance();
        double ms = (System.nanoTime() - start) / 1e6;
        if (ms >= enter + hold + exit) {
            clear();
            return;
        }
        boolean entering = ms < enter;
        double t = entering ? ms / enter : ms < enter + hold ? 1 : 1 - (ms - enter - hold) / exit;
        double eased = t * t * (3 - 2 * t);
        float cx = (float) (gui.guiWidth() * x), cy = (float) (gui.guiHeight() * y);
        int h = mc.font.lineHeight * lines.size();
        if (type.equals("Genshin")) cy -= (float) ((1 - eased) * offset * mc.font.lineHeight);
        double alpha = type.equals("Wipe") || (type.equals("Typewriter") && entering) ? 1 : eased;
        int a = (int) Math.round(255 * alpha);
        if (a == 0) return;
        int remaining =
                type.equals("Typewriter") && entering ? (int) Math.floor(t * graphemes) : graphemes;
        boolean clip = type.equals("Wipe");
        if (clip) {
            double left = cx - width / 2.0, revealed = width * eased;
            if (origin.equals("right")) left += width - revealed;
            if (origin.equals("center")) left += (width - revealed) / 2;
            gui.enableScissor(
                    (int) Math.floor(left),
                    (int) Math.floor(cy - h / 2.0),
                    (int) Math.ceil(left + revealed),
                    (int) Math.ceil(cy + h / 2.0));
        }
        var pose = gui.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        if (type.equals("Zoom")) pose.scale((float) (fromScale + (1 - fromScale) * eased));
        pose.translate(0, -h / 2f);
        for (var row : lines) {
            int count = Math.min(remaining, row.ends.size());
            if (count > 0) {
                var draw = row.text;
                if (count < row.ends.size()) {
                    int limit = row.ends.get(count - 1);
                    draw =
                            sink -> {
                                int[] index = {0};
                                return row.text.accept(
                                        (i, style, cp) ->
                                                index[0]++ < limit && sink.accept(i, style, cp));
                            };
                }
                pose.pushMatrix();
                pose.translate(-row.width / 2f, 0);
                gui.text(mc.font, draw, 0, 0, (a << 24) | 0xffffff);
                pose.popMatrix();
            }
            remaining -= count;
            pose.translate(0, mc.font.lineHeight);
        }
        pose.popMatrix();
        if (clip) gui.disableScissor();
    }
}
