package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.AreaHintsClient.*;
import static io.github.imsng1019.ahr.client.MenuPage.tr;

import com.google.gson.*;

import io.github.imsng1019.ahr.data.*;
import io.github.imsng1019.ahr.fabric.Wire;
import io.github.imsng1019.ahr.geometry.*;

import net.minecraft.network.chat.*;

import java.util.*;
import java.util.function.Consumer;

final class RegionEditor {
    private final AreaHintsClient client;
    private static final List<String> TYPES = List.of("sphere", "cylinder", "prism", "polyhedron");

    private static final class Draft {
        final String dimension, digest;
        final List<String> original;
        String parent, identifier, expression;
        JsonObject fields;
        JsonArray parts;
        List<PrimitiveOutline> outlines;

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
    private WorldSettings pendingSettings;
    private WorldOutlines.Editing preview;

    RegionEditor(AreaHintsClient client) {
        this.client = client;
    }

    void reset() {
        draft = null;
        preview = null;
        primitive = null;
        ticket = null;
        pendingSettings = null;
    }

    void dimensionChanged() {
        // World settings and their pending save belong to the whole connection.
        if (pendingSettings == null) reset();
    }

    boolean marking() {
        return primitive != null;
    }

    WorldOutlines.Editing outline(String dimension) {
        if (draft == null || !draft.dimension.equals(dimension)) return null;
        if (preview == null) {
            if (draft.outlines == null)
                draft.outlines =
                        draft.parts.asList().stream()
                                .map(e -> PrimitiveOutline.read(e.getAsJsonObject()))
                                .toList();
            preview =
                    new WorldOutlines.Editing(
                            draft.original,
                            draft.outlines,
                            primitive == null ? -1 : partIndex,
                            primitive == null ? null : PrimitiveOutline.from(primitive),
                            primitive == null ? List.of() : List.copyOf(primitive.points),
                            primitive != null,
                            primitive != null && primitive.radial());
        }
        return preview;
    }

    void mark(Point p) {
        if (!marking()) return;
        if (!primitive.mark(p)) message(tr("thirdMark"));
        else {
            preview = null;
            message(tr("marked", primitive.points.size() - 1, p.json().toString()));
        }
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
                    pick(
                            client.regions.dimension(dimension()).tree().root,
                            null,
                            "select",
                            this::begin,
                            this::open);
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
        preview = null;
        regionMenu();
    }

    static void pick(
            RegionTree.Node node,
            List<String> excluded,
            String action,
            Consumer<List<String>> select,
            Runnable cancel) {
        var p =
                new MenuPage(
                        Component.literal(Json.path(node.path) + "  " + HintHud.plain(mc(), node)),
                        node.parent == null
                                ? cancel
                                : () -> pick(node.parent, excluded, action, select, cancel));
        p.button(action, () -> select.accept(node.path));
        for (var entry : node.children.entrySet()) {
            var child = entry.getValue();
            if (!child.path.equals(excluded))
                p.button(
                        Component.literal(entry.getKey() + "  " + HintHud.plain(mc(), child)),
                        () -> pick(child, excluded, action, select, cancel));
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
        var edited = draft;
        var form =
                new RegionPropertiesForm(
                        client.regions.dimension(edited.dimension).tree(),
                        edited.original,
                        edited.parent,
                        edited.identifier,
                        edited.fields,
                        client.settings.hints());
        var p =
                page(
                        "properties",
                        () -> {
                            if (draft != edited) {
                                open();
                                return;
                            }
                            var fields = form.value();
                            edited.fields = fields;
                            edited.parent = form.parent;
                            edited.identifier = form.identifier;
                            regionMenu();
                        });
        p.contents(form::controls);
        show(p);
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
        preview = null;
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
        if (primitive.projected())
            p.field(
                    "height",
                    primitive.height,
                    s -> {
                        primitive.height = s;
                        preview = null;
                    });
        if (!primitive.radial())
            p.button(
                    tr("infinity", primitive.infinity),
                    () -> {
                        primitive.infinity = !primitive.infinity;
                        preview = null;
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
                                    preview = null;
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
                    preview = null;
                    back.run();
                });
        if (i >= 0)
            p.button(
                    "delete",
                    () -> {
                        if (face) list.remove(i);
                        else primitive.deletePoint(i);
                        preview = null;
                        back.run();
                    });
        show(p);
    }

    private void finishPart(boolean prune) {
        var obj = primitive.save(prune);
        if (partIndex < 0) draft.parts.add(obj);
        else draft.parts.set(partIndex, obj);
        primitive = null;
        draft.outlines = null;
        preview = null;
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
        if (pendingSettings != null) {
            var form = pendingSettings;
            pendingSettings = null;
            if (Json.bool(msg.get("ok"))) {
                if (mc().gui.screen() == form.screen) open();
            } else form.failed(Wire.field(msg, "error"));
            return;
        }
        if (Json.bool(msg.get("ok"))) {
            reset();
            if (mc().gui.screen() instanceof MenuPage) open();
            message(tr("command.saved"));
        } else message(Component.literal(Wire.field(msg, "error")));
    }

    private void settings() {
        var before = client.settings.copy();
        var config = before.deepCopy();
        var hints = Json.object(config, "hints").deepCopy();
        var form = new HintOptionsForm(hints, new JsonObject(), false);
        var p =
                page(
                        "settings",
                        () -> {
                            boolean wasEnabled = client.settings.enabled();
                            client.settings.saveEdits(before, config, form.value());
                            if (wasEnabled != client.settings.enabled()) {
                                client.detection.reset();
                                client.hud.clear();
                            }
                            open();
                        });
        p.contents(
                screen -> {
                    screen.interval(
                            Presentation.interval(config),
                            v -> config.addProperty("detectionIntervalSeconds", v));
                    screen.toggle(
                            "automatic",
                            !config.has("enabled") || Json.bool(config.get("enabled")),
                            false,
                            v -> config.addProperty("enabled", v));
                    screen.toggle(
                            "showPrimitiveOutlines",
                            config.has("showPrimitiveOutlines")
                                    && Json.bool(config.get("showPrimitiveOutlines")),
                            false,
                            v -> config.addProperty("showPrimitiveOutlines", v));
                    for (String key :
                            List.of("useRichText", "showFullPath", "showAncestorOnExit")) {
                        boolean value =
                                hints.has(key)
                                        ? Json.bool(hints.get(key))
                                        : key.equals("useRichText");
                        screen.toggle(key, value, false, v -> hints.addProperty(key, v));
                    }
                    screen.field(
                            "separator",
                            hints.has("separator") ? Json.string(hints.get("separator")) : "·",
                            s -> hints.addProperty("separator", s));
                    form.controls(screen);
                });
        show(p);
    }

    private void worldSettings() {
        requireAuthor();
        new WorldSettings().screen.show();
    }

    private final class WorldSettings {
        private final String digest = Json.digest(client.regions.settings.toString());
        private final JsonObject original = Json.object(client.regions.settings, "hints");
        private final JsonObject hints = original.deepCopy();
        private boolean useSeparator = hints.has("separator");
        private String separator = useSeparator ? Json.string(hints.get("separator")) : "";
        private boolean rejected;
        private final MenuPage screen = page("worldSettings", this::leave);

        WorldSettings() {
            screen.contents(
                    p -> {
                        for (String key :
                                List.of("useRichText", "showFullPath", "showAncestorOnExit")) {
                            Boolean value = hints.has(key) ? Json.bool(hints.get(key)) : null;
                            p.toggle(
                                    key,
                                    value,
                                    true,
                                    v -> {
                                        if (v == null) hints.remove(key);
                                        else hints.addProperty(key, v);
                                    });
                        }
                        p.toggle("overrideSeparator", useSeparator, false, v -> useSeparator = v);
                        p.field("separator", separator, s -> separator = s);
                        if (rejected) p.button("discard", RegionEditor.this::open);
                    });
        }

        private void leave() {
            var result = hints.deepCopy();
            if (useSeparator) result.addProperty("separator", separator);
            else result.remove("separator");
            if (result.equals(original)) {
                open();
                return;
            }
            try {
                requireAuthor();
                var msg = Wire.message("settings");
                msg.addProperty("digest", digest);
                msg.add("hints", result);
                submit(msg, UUID.randomUUID().toString());
                pendingSettings = this;
                screen.waitingForSave();
            } catch (RuntimeException e) {
                failed(Objects.toString(e.getMessage(), "Invalid world settings"));
            }
        }

        void failed(String message) {
            rejected = true;
            screen.saveFailed(message);
        }
    }
}
