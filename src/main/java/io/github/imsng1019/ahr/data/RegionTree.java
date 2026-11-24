package io.github.imsng1019.ahr.data;

import com.google.gson.*;

import io.github.imsng1019.ahr.geometry.*;

import java.util.*;

public final class RegionTree {
    public static final class Node {
        public final Node parent;
        public final List<String> path;
        public final JsonObject json;
        public final Map<String, Node> children;
        public final RegionPredicate predicate;
        public final Bounds own;
        public final Bounds subtree;

        private Node(Node parent, List<String> path, JsonObject json, Node previous) {
            this.parent = parent;
            this.path = path;
            this.json = json;
            Presentation.validateHints(Json.object(json, "hint"));
            JsonObject name = Json.object(json, "name");
            if (parent != null || name.has("fallback")) Json.string(name.get("fallback"));
            Json.object(name, "translations").asMap().values().forEach(Json::string);
            if (parent == null) {
                predicate = null;
                own = Bounds.EMPTY;
            } else {
                var geometry = json.getAsJsonObject("predicate");
                predicate =
                        previous != null && geometry.equals(previous.json.get("predicate"))
                                ? previous.predicate
                                : new RegionPredicate(geometry, false);
                own = Bounds.read(json.get("aabb"));
            }
            var nodes = new LinkedHashMap<String, Node>();
            var entries = Json.object(json, "children");
            boolean changed =
                    previous == null
                            || !previous.own.equals(own)
                            || !previous.children.keySet().equals(entries.keySet());
            for (var entry : entries.entrySet()) {
                var segments = new ArrayList<>(path);
                segments.add(entry.getKey());
                var oldChild = previous == null ? null : previous.children.get(entry.getKey());
                var child =
                        new Node(
                                this,
                                List.copyOf(segments),
                                entry.getValue().getAsJsonObject(),
                                oldChild);
                nodes.put(entry.getKey(), child);
                changed |= oldChild == null || !oldChild.subtree.equals(child.subtree);
            }
            children = Collections.unmodifiableMap(nodes);
            if (changed) {
                Bounds box = own;
                for (var child : children.values()) box = box.union(child.subtree);
                subtree = box;
            } else subtree = previous.subtree;
        }

        public boolean enabled() {
            var hint = Json.object(json, "hint");
            return !hint.has("enabled") || Json.bool(hint.get("enabled"));
        }

        public String name(String language, String dimensionName) {
            var names = Json.object(json, "name");
            var translations = Json.object(names, "translations");
            if (translations.has(language)) return Json.string(translations.get(language));
            return names.has("fallback") ? Json.string(names.get("fallback")) : dimensionName;
        }

        private void collect(Point p, List<Node> hits) {
            if (!subtree.contains(p)) return;
            if (predicate != null && own.contains(p) && predicate.contains(p)) hits.add(this);
            for (var child : children.values()) child.collect(p, hits);
        }
    }

    public final Node root;

    public RegionTree(JsonObject json) {
        this(json, null);
    }

    public RegionTree(JsonObject json, RegionTree previous) {
        root = new Node(null, List.of(), json, previous == null ? null : previous.root);
    }

    public Node find(List<String> path) {
        Node n = root;
        for (var id : path) {
            n = n.children.get(id);
            if (n == null) throw new IllegalArgumentException("Unknown region " + Json.path(path));
        }
        return n;
    }

    public List<Node> matches(Point p) {
        var hits = new ArrayList<Node>();
        root.collect(p, hits);
        return hits;
    }

    public List<Node> eligible(Point p) {
        var hits = new ArrayList<Node>();
        if (root.enabled()) hits.add(root);
        for (var n : matches(p)) if (n.enabled()) hits.add(n);
        return hits;
    }
}
