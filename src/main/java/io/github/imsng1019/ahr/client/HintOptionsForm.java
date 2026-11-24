package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.MenuPage.tr;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;

import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.util.*;

/** Working position and animation on a global settings or region properties page. */
final class HintOptionsForm {
    private final JsonObject hints;
    private final JsonObject inherited;
    private final boolean regional;
    private final String[] coordinates = {"", ""};

    HintOptionsForm(JsonObject hints, JsonObject global, boolean regional) {
        this.hints = hints;
        this.inherited = global;
        this.regional = regional;
        if (hints.has("position")) {
            var pair = hints.getAsJsonArray("position");
            for (int i = 0; i < 2; i++) coordinates[i] = pair.get(i).getAsString();
        }
    }

    void controls(MenuPage page) {
        page.startRow();
        for (int i = 0; i < 2; i++) {
            int axis = i;
            page.field(
                    tr(i == 0 ? "positionX" : "positionY"),
                    coordinates[i],
                    valueHint(inheritedPosition(i), regional),
                    s -> coordinates[axis] = s);
        }
        var effective = Presentation.animation(hints, inherited);
        Component current = effect(effective.get("type").getAsString());
        if (!hints.has("animation"))
            current = tr(regional ? "globalValue" : "defaultValue", current);
        page.button(tr("chooseAnimation", current), () -> chooseAnimation(page));
        page.button("animationParameters", () -> parameters(page));
    }

    JsonObject value() {
        var result = hints.deepCopy();
        if (coordinates[0].isBlank() && coordinates[1].isBlank()) {
            result.remove("position");
        } else {
            var pair = new JsonArray();
            for (int i = 0; i < 2; i++) {
                var label = tr(i == 0 ? "positionX" : "positionY");
                BigDecimal v =
                        coordinates[i].isBlank()
                                ? inheritedPosition(i)
                                : decimal(coordinates[i], label);
                if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0)
                    throw problem("invalidPosition", label);
                pair.add(v);
            }
            result.add("position", pair);
        }
        Presentation.validateHints(result);
        return result;
    }

    private BigDecimal inheritedPosition(int axis) {
        if (inherited.has("position"))
            return inherited.getAsJsonArray("position").get(axis).getAsBigDecimal();
        return BigDecimal.valueOf(Presentation.position(inherited, inherited, axis));
    }

    private static Component valueHint(BigDecimal value, boolean fromGlobal) {
        return tr(
                fromGlobal ? "globalValue" : "defaultValue",
                BigDecimal.valueOf(value.doubleValue()).stripTrailingZeros().toPlainString());
    }

    private static Component effect(String type) {
        return tr("effect." + type);
    }

    private void chooseAnimation(MenuPage owner) {
        var picker = new MenuPage(tr("animation"), owner::show);
        picker.button(
                regional ? "useGlobalAnimation" : "useDefaultAnimation",
                () -> {
                    hints.remove("animation");
                    owner.show();
                });
        for (String type : Presentation.EFFECTS) {
            picker.button(
                    effect(type),
                    () -> {
                        var current = Json.object(hints, "animation");
                        if (!current.has("type")
                                || !type.equals(Json.string(current.get("type")))) {
                            var selected = new JsonObject();
                            selected.addProperty("type", type);
                            hints.add("animation", selected);
                        }
                        owner.show();
                    });
        }
        picker.show();
    }

    private static List<String> parametersFor(String type) {
        return switch (type) {
            case "Instant" -> List.of("holdMs");
            case "Typewriter" -> List.of("graphemesPerSecond", "holdMs", "exitMs");
            case "Genshin" -> List.of("enterMs", "holdMs", "exitMs", "offsetLines");
            case "Zoom" -> List.of("enterMs", "holdMs", "exitMs", "fromScale");
            default -> List.of("enterMs", "holdMs", "exitMs");
        };
    }

    private void parameters(MenuPage owner) {
        var selected = Json.object(hints, "animation");
        String type = Presentation.animation(hints, inherited).get("type").getAsString();
        var typeOnly = new JsonObject();
        typeOnly.addProperty("type", type);
        var node = new JsonObject();
        node.add("animation", typeOnly);
        var defaults = Presentation.animation(node, inherited);
        var values = new LinkedHashMap<String, String>();
        for (String key : parametersFor(type))
            values.put(key, selected.has(key) ? selected.get(key).getAsString() : "");
        String[] origin = {selected.has("origin") ? Json.string(selected.get("origin")) : null};
        var page =
                new MenuPage(
                        tr("parametersFor", effect(type)),
                        () -> {
                            var candidate = selected.deepCopy();
                            candidate.addProperty("type", type);
                            for (var item : values.entrySet()) {
                                String key = item.getKey(), text = item.getValue();
                                if (text.isBlank()) {
                                    candidate.remove(key);
                                    continue;
                                }
                                Component label = tr("param." + key);
                                var value = decimal(text, label);
                                boolean positive =
                                        key.equals("fromScale") || key.equals("graphemesPerSecond");
                                if (value.signum() < 0 || (positive && value.doubleValue() == 0))
                                    throw problem(
                                            positive ? "positiveValue" : "nonnegativeValue", label);
                                candidate.addProperty(key, value);
                            }
                            if (type.equals("Wipe")) {
                                if (origin[0] == null) candidate.remove("origin");
                                else candidate.addProperty("origin", origin[0]);
                            }
                            if (hints.has("animation") || candidate.size() > 1)
                                hints.add("animation", candidate);
                            owner.show();
                        });
        page.contents(
                p -> {
                    for (var item : values.entrySet()) {
                        String key = item.getKey();
                        p.field(
                                tr("param." + key),
                                item.getValue(),
                                valueHint(
                                        BigDecimal.valueOf(Presentation.parameter(defaults, key)),
                                        regional && defaults.has(key)),
                                s -> values.put(key, s));
                    }
                    if (type.equals("Wipe")) {
                        String base =
                                defaults.has("origin")
                                        ? defaults.get("origin").getAsString()
                                        : "left";
                        Component label =
                                origin[0] == null
                                        ? tr(
                                                regional && defaults.has("origin")
                                                        ? "globalValue"
                                                        : "defaultValue",
                                                tr("origin." + base))
                                        : tr("origin." + origin[0]);
                        p.button(
                                tr("wipeOrigin", label),
                                () -> {
                                    List<String> directions =
                                            Arrays.asList(null, "left", "right", "center");
                                    origin[0] =
                                            directions.get(
                                                    (directions.indexOf(origin[0]) + 1)
                                                            % directions.size());
                                    p.refresh();
                                });
                    }
                });
        page.show();
    }

    private static BigDecimal decimal(String text, Component label) {
        try {
            var value = new BigDecimal(text.trim());
            if (Double.isFinite(value.doubleValue())) return value;
        } catch (NumberFormatException ignored) {
        }
        throw problem("finiteValue", label);
    }

    private static IllegalArgumentException problem(String key, Component label) {
        return new IllegalArgumentException(tr(key, label).getString());
    }
}
