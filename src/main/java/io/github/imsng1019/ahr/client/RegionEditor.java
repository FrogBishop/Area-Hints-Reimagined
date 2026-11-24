package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.AreaHintsClient.*;
import static io.github.imsng1019.ahr.client.MenuPage.tr;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.Wire;
import io.github.imsng1019.ahr.geometry.*;

import net.minecraft.network.chat.*;

import java.util.*;

final class RegionEditor {
    private final AreaHintsClient client;
    private static final List<String> TYPES = List.of("sphere", "cylinder", "prism", "polyhedron");

    private static final class Draft {
        final String dimension, digest;
        final List<String> original;
        String parent, identifier, expression;
        JsonObject fields;
        JsonArray parts;

        Draft(String dimension, WorldFiles.Dimension data, List<String> original) {
            this.dimension = dimension;
            this.digest = data.digest();
            this.original = original;
            var node = original == null ? new JsonObject() : data.tree().find(original).json;
            parent =
                    Json.path(
                                    original == null || original.isEmpty()
                                            ? List.of()
                                            : original.subList(0, original.size() - 1))
                            .toString();
            identifier = original == null || original.isEmpty() ? "" : original.getLast();
            fields = new JsonObject();
            for (String key : List.of("name", "hint"))
                if (node.has(key)) fields.add(key, node.get(key).deepCopy());
            if (original == null) {
                var name = new JsonObject();
                name.addProperty("fallback", "");
                fields.add("name", name);
            }
            var predicate = Json.object(node, "predicate");
            parts =
                    predicate.has("primitives")
                            ? predicate.getAsJsonArray("primitives").deepCopy()
                            : new JsonArray();
            expression =
                    predicate.has("expression") ? Json.string(predicate.get("expression")) : "0";
        }

        boolean root() {
            return original != null && original.isEmpty();
        }
    }

    private Draft draft;
    private PrimitiveDraft primitive;
    private int partIndex;
    private String ticket;

    RegionEditor(AreaHintsClient client) {
        this.client = client;
    }

    void reset() {
        draft = null;
        primitive = null;
        ticket = null;
    }

    boolean marking() {
        return primitive != null;
    }

    void mark(Point p) {
        if (!marking()) return;
        if (!primitive.mark(p)) message(tr("thirdMark"));
        else message(tr("marked", primitive.points.size() - 1, p.json().toString()));
    }

    private static void show(MenuPage p) {
        mc().gui.setScreen(p);
    }

    private static MenuPage page(String title, Runnable back) {
        return new MenuPage(tr(title), back);
    }

    void open() {
        if (primitive != null) {
            primitiveMenu();
            return;
        }
        if (draft != null) {
            regionMenu();
            return;
        }
        var p = page("menu", null);
        p.button("new", () -> begin(null));
        p.button(
                "editRegion",
                () -> {
                    requireAuthor();
                    pick(client.regions.dimension(dimension()).tree().root);
                });
        p.button("settings", this::settings);
        if (client.regions.ready && client.regions.author)
            p.button("worldSettings", this::worldSettings);
        show(p);
    }

    private void requireAuthor() {
        if (!client.regions.ready) throw new IllegalStateException(tr("unavailable").getString());
        if (!client.regions.author) throw new IllegalStateException(tr("permission").getString());
    }

    private void begin(List<String> path) {
        requireAuthor();
        draft = new Draft(dimension(), client.regions.dimension(dimension()), path);
        regionMenu();
    }

    private void pick(RegionTree.Node node) {
        var p =
                new MenuPage(
                        Component.literal(Json.path(node.path) + "  " + HintHud.plain(mc(), node)),
                        node.parent == null ? this::open : () -> pick(node.parent));
        p.button("select", () -> begin(node.path));
        for (var entry : node.children.entrySet()) {
            var n = entry.getValue();
            p.button(
                    Component.literal(entry.getKey() + "  " + HintHud.plain(mc(), n)),
                    () -> pick(n));
        }
        show(p);
    }

    private void regionMenu() {
        var p = page("editRegion", null);
        p.button("save", this::saveRegion);
        p.button("properties", this::properties);
        if (!draft.root()) {
            p.field("expression", draft.expression, s -> draft.expression = s);
            p.button("editPart", this::choosePart);
            for (String type : TYPES) p.button("add." + type, () -> startPart(type, -1));
        }
        p.button(
                "discard",
                () ->
                        confirmDiscard(
                                () -> {
                                    reset();
                                    open();
                                },
                                this::regionMenu));
        show(p);
    }

    private void confirmDiscard(Runnable yes, Runnable back) {
        var p = page("discardConfirm", back);
        p.button("discard", yes);
        show(p);
    }

    private void properties() {
        var p = page("properties", this::regionMenu);
        var named = Json.object(draft.fields, "name");
        var hint = Json.object(draft.fields, "hint");
        var values = new LinkedHashMap<String, String>();
        String[] parent = {draft.parent}, identifier = {draft.identifier};
        boolean[] custom = {named.has("fallback")};
        String[] fallback = {custom[0] ? Json.string(named.get("fallback")) : ""};
        Boolean[] enabled = {hint.has("enabled") ? Json.bool(hint.get("enabled")) : null};
        if (!draft.root()) {
            p.field("parent", parent[0], s -> parent[0] = s);
            p.field("identifier", identifier[0], s -> identifier[0] = s);
        } else {
            p.toggle("nameFallback", custom[0], false, v -> custom[0] = v);
        }
        p.field("fallback", fallback[0], s -> fallback[0] = s);
        jsonField(p, values, named, "translations");
        p.toggle("enabled", enabled[0], true, v -> enabled[0] = v);
        for (String key : List.of("richText", "position", "animation"))
            jsonField(p, values, hint, key);
        p.button(
                "apply",
                () -> {
                    var name = named.deepCopy();
                    var hints = hint.deepCopy();
                    if (custom[0] || !draft.root()) name.addProperty("fallback", fallback[0]);
                    else name.remove("fallback");
                    if (enabled[0] == null) hints.remove("enabled");
                    else hints.addProperty("enabled", enabled[0]);
                    apply(values, name, "translations");
                    apply(values, hints, "richText", "position", "animation");
                    Json.object(name, "translations").asMap().values().forEach(Json::string);
                    Presentation.validateHints(hints);
                    var rich = Json.object(hints, "richText");
                    var ops =
                            mc().level
                                    .registryAccess()
                                    .createSerializationContext(JsonOps.INSTANCE);
                    if (rich.has("fallback"))
                        ComponentSerialization.CODEC.parse(ops, rich.get("fallback")).getOrThrow();
                    for (var v : Json.object(rich, "translations").asMap().values())
                        ComponentSerialization.CODEC.parse(ops, v).getOrThrow();
                    Json.path(Json.parse(parent[0]));
                    draft.parent = parent[0];
                    draft.identifier = identifier[0];
                    optional(draft.fields, "name", name);
                    optional(draft.fields, "hint", hints);
                    regionMenu();
                });
        show(p);
    }

    private static void jsonField(
            MenuPage page, Map<String, String> values, JsonObject obj, String key) {
        String text = obj.has(key) ? obj.get(key).toString() : "";
        values.put(key, text);
        page.field(key, text, s -> values.put(key, s));
    }

    private static void apply(Map<String, String> values, JsonObject obj, String... keys) {
        for (String k : keys) {
            String s = values.get(k);
            if (s.isBlank()) obj.remove(k);
            else obj.add(k, Json.parse(s));
        }
    }

    private static void optional(JsonObject parent, String key, JsonObject value) {
        if (value.isEmpty()) parent.remove(key);
        else parent.add(key, value);
    }

    private void choosePart() {
        var p = page("editPart", this::regionMenu);
        for (int i = 0; i < draft.parts.size(); i++) {
            int index = i;
            String type = draft.parts.get(i).getAsJsonObject().keySet().iterator().next();
            p.button(Component.literal(i + "  ").append(tr(type)), () -> startPart(type, index));
        }
        show(p);
    }

    private void startPart(String type, int index) {
        var saved =
                index < 0 ? null : draft.parts.get(index).getAsJsonObject().getAsJsonObject(type);
        primitive = new PrimitiveDraft(type, saved, point().y());
        partIndex = index;
        mc().gui.setScreen(null);
    }

    private void primitiveMenu() {
        var p = page(primitive.type, null);
        p.button(
                "savePart",
                () -> {
                    if (primitive.unused()) {
                        var warning = page("unused", this::primitiveMenu);
                        warning.button("keepUnused", () -> finishPart(false));
                        warning.button("deleteUnused", () -> finishPart(true));
                        show(warning);
                    } else finishPart(false);
                });
        if (primitive.projected()) p.field("height", primitive.height, s -> primitive.height = s);
        if (!primitive.radial())
            p.button(
                    tr("infinity", primitive.infinity),
                    () -> {
                        primitive.infinity = !primitive.infinity;
                        primitiveMenu();
                    });
        if (primitive.type.equals("polyhedron")) p.button("faces", this::faces);
        for (int i = 0; i < primitive.points.size(); i++) {
            int index = i;
            p.button(
                    Component.literal(i + "  " + primitive.points.get(i).json()),
                    () -> pointForm(index, false));
        }
        p.button(
                "discard",
                () ->
                        confirmDiscard(
                                () -> {
                                    primitive = null;
                                    regionMenu();
                                },
                                this::primitiveMenu));
        show(p);
    }

    private void faces() {
        var p = page("faces", this::primitiveMenu);
        p.button("addFace", () -> pointForm(-1, true));
        for (int i = 0; i < primitive.faces.size(); i++) {
            int index = i;
            p.button(
                    Component.literal(i + "  " + primitive.faces.get(i).json()),
                    () -> pointForm(index, true));
        }
        show(p);
    }

    private void pointForm(int i, boolean face) {
        Runnable back = face ? this::faces : this::primitiveMenu;
        var p = page(face ? "face" : "point", back);
        var list = face ? primitive.faces : primitive.points;
        String[] value = {i < 0 ? "[0,1,2]" : list.get(i).json().toString()};
        p.field(face ? "indices" : "coordinates", value[0], s -> value[0] = s);
        p.button(
                "apply",
                () -> {
                    var v = Point.read(Json.parse(value[0]));
                    if (face)
                        for (int n : new int[] {v.x(), v.y(), v.z()})
                            if (n < 0 || n >= primitive.points.size())
                                throw new IllegalArgumentException("Unknown vertex " + n);
                    if (i < 0) list.add(v);
                    else list.set(i, v);
                    back.run();
                });
        if (i >= 0)
            p.button(
                    "delete",
                    () -> {
                        if (face) list.remove(i);
                        else primitive.deletePoint(i);
                        back.run();
                    });
        show(p);
    }

    private void finishPart(boolean prune) {
        var obj = primitive.save(prune);
        if (partIndex < 0) draft.parts.add(obj);
        else draft.parts.set(partIndex, obj);
        primitive = null;
        regionMenu();
    }

    private void saveRegion() {
        requireAuthor();
        if (ticket != null) throw new IllegalStateException(tr("saving").getString());
        var target = new ArrayList<String>();
        var fields = draft.fields.deepCopy();
        if (!draft.root()) {
            target.addAll(Json.path(Json.parse(draft.parent)));
            target.add(draft.identifier);
            var pred = new JsonObject();
            pred.add("primitives", draft.parts);
            pred.addProperty("expression", draft.expression);
            new RegionPredicate(pred, true);
            fields.add("predicate", pred);
        }
        var msg = Wire.message("edit");
        String id = UUID.randomUUID().toString();
        msg.addProperty("ticket", id);
        msg.addProperty("dimension", draft.dimension);
        msg.addProperty("digest", draft.digest);
        if (draft.original != null) msg.add("original", Json.path(draft.original));
        msg.add("path", Json.path(target));
        msg.add("fields", fields);
        submit(msg, id);
    }

    private void submit(JsonObject msg, String id) {
        if (ticket != null) throw new IllegalStateException(tr("saving").getString());
        msg.addProperty("ticket", id);
        ClientRegions.send(msg);
        ticket = id;
    }

    void result(JsonObject msg) {
        if (!msg.has("ticket") || !Objects.equals(ticket, Wire.field(msg, "ticket"))) return;
        ticket = null;
        if (Json.bool(msg.get("ok"))) {
            reset();
            if (mc().gui.screen() instanceof MenuPage) open();
            message(tr("command.saved"));
        } else message(Component.literal(Wire.field(msg, "error")));
    }

    private void settings() {
        var p = page("settings", this::open);
        var config = client.settings.copy();
        var hints = Json.object(config, "hints").deepCopy();
        var values = new LinkedHashMap<String, String>();
        p.interval(
                client.settings.interval(), v -> config.addProperty("detectionIntervalSeconds", v));
        p.toggle(
                "automatic",
                client.settings.enabled(),
                false,
                v -> config.addProperty("enabled", v));
        for (String key : List.of("useRichText", "showFullPath", "showAncestorOnExit")) {
            boolean value = hints.has(key) ? Json.bool(hints.get(key)) : key.equals("useRichText");
            p.toggle(key, value, false, v -> hints.addProperty(key, v));
        }
        p.field(
                "separator",
                hints.has("separator") ? Json.string(hints.get("separator")) : "·",
                s -> hints.addProperty("separator", s));
        for (String key : List.of("position", "animation")) jsonField(p, values, hints, key);
        p.button(
                "save",
                () -> {
                    apply(values, hints, values.keySet().toArray(String[]::new));
                    optional(config, "hints", hints);
                    boolean before = client.settings.enabled();
                    client.settings.save(config);
                    if (before != client.settings.enabled()) {
                        client.detection.reset();
                        client.hud.clear();
                    }
                    open();
                });
        show(p);
    }

    private void worldSettings() {
        requireAuthor();
        var p = page("worldSettings", this::open);
        String digest = Json.digest(client.regions.settings.toString());
        var hints = Json.object(client.regions.settings, "hints").deepCopy();
        for (String key : List.of("useRichText", "showFullPath", "showAncestorOnExit")) {
            Boolean initial = hints.has(key) ? Json.bool(hints.get(key)) : null;
            p.toggle(
                    key,
                    initial,
                    true,
                    v -> {
                        if (v == null) hints.remove(key);
                        else hints.addProperty(key, v);
                    });
        }
        boolean[] useSeparator = {hints.has("separator")};
        String[] value = {useSeparator[0] ? Json.string(hints.get("separator")) : ""};
        p.toggle("overrideSeparator", useSeparator[0], false, v -> useSeparator[0] = v);
        p.field("separator", value[0], s -> value[0] = s);
        p.button(
                "save",
                () -> {
                    if (useSeparator[0]) hints.addProperty("separator", value[0]);
                    else hints.remove("separator");
                    var msg = Wire.message("settings");
                    msg.addProperty("digest", digest);
                    msg.add("hints", hints);
                    submit(msg, UUID.randomUUID().toString());
                });
        show(p);
    }
}
