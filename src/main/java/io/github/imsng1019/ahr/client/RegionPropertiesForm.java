package io.github.imsng1019.ahr.client;

import static io.github.imsng1019.ahr.client.AreaHintsClient.mc;
import static io.github.imsng1019.ahr.client.MenuPage.tr;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;

import io.github.imsng1019.ahr.data.*;

import net.minecraft.network.chat.*;

import java.util.*;

final class RegionPropertiesForm {
    private final RegionTree tree;
    private final List<String> original;
    private final JsonObject fields, name, hint;
    private final Translations names;
    private final HintOptionsForm presentation;
    private boolean customFallback;
    private String fallback;
    String parent, identifier;

    RegionPropertiesForm(
            RegionTree tree,
            List<String> original,
            String parent,
            String identifier,
            JsonObject fields,
            JsonObject global) {
        this.tree = tree;
        this.original = original;
        this.parent = parent;
        this.identifier = identifier;
        this.fields = fields.deepCopy();
        name = Json.object(this.fields, "name").deepCopy();
        hint = Json.object(this.fields, "hint").deepCopy();
        customFallback = name.has("fallback");
        fallback = customFallback ? Json.string(name.get("fallback")) : "";
        names = new Translations(Json.object(name, "translations"), false);
        presentation = new HintOptionsForm(hint, global, true);
    }

    private boolean root() {
        return original != null && original.isEmpty();
    }

    void controls(MenuPage p) {
        if (root()) {
            p.toggle("nameFallback", customFallback, false, v -> customFallback = v);
        } else {
            p.field("parent", parent, s -> parent = s);
            p.button(
                    "browse",
                    () ->
                            RegionEditor.pick(
                                    tree.root,
                                    original,
                                    "chooseParent",
                                    path -> {
                                        parent = Json.path(path).toString();
                                        p.show();
                                    },
                                    p::show));
            p.field("identifier", identifier, s -> identifier = s);
        }
        p.field("fallback", fallback, s -> fallback = s);
        p.startRow();
        names.addButton(p);
        p.button("richText", () -> richText(p));
        names.rows(p);
        Boolean enabled = hint.has("enabled") ? Json.bool(hint.get("enabled")) : null;
        p.toggle(
                "enabled",
                enabled,
                true,
                v -> {
                    if (v == null) hint.remove("enabled");
                    else hint.addProperty("enabled", v);
                });
        presentation.controls(p);
    }

    JsonObject value() {
        if (!root()) validateParent();
        var result = fields.deepCopy();
        var named = name.deepCopy();
        if (customFallback || !root()) named.addProperty("fallback", fallback);
        else named.remove("fallback");
        Json.putOptionalObject(named, "translations", names.json());
        Json.putOptionalObject(result, "name", named);
        Json.putOptionalObject(result, "hint", presentation.value());
        return result;
    }

    private void validateParent() {
        List<String> path;
        try {
            path = Json.path(Json.parse(parent));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(tr("invalidParent").getString());
        }
        if (original != null
                && path.size() >= original.size()
                && path.subList(0, original.size()).equals(original))
            throw new IllegalArgumentException(tr("parentCycle").getString());
        try {
            tree.find(path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    tr("missingParent", Json.path(path).toString()).getString());
        }
    }

    private void richText(MenuPage owner) {
        var rich = Json.object(hint, "richText").deepCopy();
        var values = new Translations(Json.object(rich, "translations"), true);
        String[] base = {rich.has("fallback") ? rich.get("fallback").toString() : ""};
        var page =
                new MenuPage(
                        tr("richText"),
                        () -> {
                            var edited = rich.deepCopy();
                            if (base[0].isBlank()) edited.remove("fallback");
                            else edited.add("fallback", component(base[0], tr("richFallback")));
                            Json.putOptionalObject(edited, "translations", values.json());
                            Json.putOptionalObject(hint, "richText", edited);
                            owner.show();
                        });
        page.contents(
                p -> {
                    p.field(
                            tr("richFallback"),
                            base[0],
                            Component.literal("{\"text\":\"…\"}"),
                            s -> base[0] = s);
                    values.addButton(p);
                    values.rows(p);
                });
        page.show();
    }

    private static JsonElement component(String raw, Component label) {
        try {
            var parsed = Json.parse(raw);
            var ops = mc().level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            ComponentSerialization.CODEC.parse(ops, parsed).getOrThrow();
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(tr("invalidComponent", label).getString(), e);
        }
    }

    private static final class Translations {
        private final Map<String, String> entries = new LinkedHashMap<>();
        private final boolean rich;

        Translations(JsonObject values, boolean rich) {
            this.rich = rich;
            values.entrySet()
                    .forEach(
                            e ->
                                    entries.put(
                                            e.getKey(),
                                            rich
                                                    ? e.getValue().toString()
                                                    : Json.string(e.getValue())));
        }

        void addButton(MenuPage owner) {
            owner.button("addLanguage", () -> languages(owner));
        }

        void rows(MenuPage owner) {
            owner.startRow();
            for (var entry : entries.entrySet()) {
                String code = entry.getKey();
                Component label = languageName(code);
                owner.field(
                        label,
                        entry.getValue(),
                        rich ? Component.literal("{\"text\":\"…\"}") : null,
                        s -> entries.put(code, s));
                owner.button(
                        tr("removeLanguage", code),
                        () -> {
                            entries.remove(code);
                            owner.refresh();
                        });
            }
        }

        JsonObject json() {
            var result = new JsonObject();
            entries.forEach(
                    (code, text) -> {
                        if (rich)
                            result.add(
                                    code,
                                    text.isBlank()
                                            ? new JsonPrimitive("")
                                            : component(text, languageName(code)));
                        else result.addProperty(code, text);
                    });
            return result;
        }

        private void languages(MenuPage owner) {
            String[] query = {""}, filter = {""};
            var manager = mc().getLanguageManager();
            var codes = new LinkedHashSet<String>();
            codes.add(manager.getSelected());
            codes.add("en_us");
            codes.addAll(manager.getLanguages().keySet());
            codes.removeAll(entries.keySet());
            var picker = new MenuPage(tr("languages"), owner::show);
            picker.contents(
                    p -> {
                        p.field("languageSearch", query[0], s -> query[0] = s);
                        p.button(
                                "filterLanguages",
                                () -> {
                                    filter[0] = query[0].strip().toLowerCase(Locale.ROOT);
                                    p.refresh();
                                });
                        for (String code : codes) {
                            Component label = languageName(code);
                            if (label.getString().toLowerCase(Locale.ROOT).contains(filter[0]))
                                p.button(label, () -> add(code, owner));
                        }
                        String custom = query[0].strip();
                        if (!filter[0].isEmpty()
                                && custom.toLowerCase(Locale.ROOT).equals(filter[0])
                                && !manager.getLanguages().containsKey(custom)
                                && !entries.containsKey(custom)
                                && custom.matches("[a-z0-9]+(?:[_-][a-z0-9]+)*"))
                            p.button(tr("customLanguage", custom), () -> add(custom, owner));
                    });
            picker.show();
        }

        private void add(String code, MenuPage owner) {
            entries.putIfAbsent(code, "");
            owner.showField(languageName(code));
        }

        private static Component languageName(String code) {
            var info = mc().getLanguageManager().getLanguage(code);
            return info == null
                    ? Component.literal(code)
                    : info.toComponent().copy().append(" · " + code);
        }
    }
}
