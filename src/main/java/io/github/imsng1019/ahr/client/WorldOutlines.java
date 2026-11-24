package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.data.RegionTree;
import io.github.imsng1019.ahr.geometry.Point;

import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.*;

final class WorldOutlines {
    static final int SAVED = 0xff47e2ea, DRAFT = 0xffffc252, GUIDE = 0xffffe9a9;
    private static final RenderStateDataKey<OutlineFrame.Geometry> GEOMETRY =
            RenderStateDataKey.create();

    private record Region(List<String> path, List<PrimitiveOutline> parts) {}

    private final List<Region> regions = new ArrayList<>();
    private RegionTree source;

    void register(AreaHintsClient client) {
        LevelExtractionEvents.END_EXTRACTION.register(
                ctx -> {
                    var state = (FabricRenderState) ctx.levelState();
                    if (!client.settings.showOutlines()) {
                        clear();
                        state.setData(GEOMETRY, null);
                        return;
                    }
                    String dimension = ctx.level().dimension().identifier().toString();
                    var tree =
                            client.regions.ready
                                    ? client.regions.dimension(dimension).tree()
                                    : null;
                    var editing = client.editor.outline(dimension);
                    var mc = Minecraft.getInstance();
                    var frame =
                            new OutlineFrame(
                                    ctx.camera().position(),
                                    16.0 * mc.options.getEffectiveRenderDistance());
                    collect(
                            frame,
                            tree,
                            editing,
                            editing != null
                                            && editing.marking
                                            && mc.player != null
                                            && mc.gui.screen() == null
                                    ? nextMark(mc)
                                    : null);
                    state.setData(GEOMETRY, frame.snapshot());
                });
        LevelRenderEvents.BEFORE_GIZMOS.register(
                ctx -> {
                    var geometry = ((FabricRenderState) ctx.levelState()).getData(GEOMETRY);
                    if (geometry == null
                            || (geometry.faces().isEmpty() && geometry.lines().isEmpty())) return;
                    try (var ignored = ctx.levelRenderer().collectPerFrameRenderThreadGizmos()) {
                        if (!geometry.faces().isEmpty())
                            Gizmos.addGizmo(
                                    (sink, opacity) -> {
                                        for (var face : geometry.faces())
                                            sink.addQuad(
                                                    face.a(),
                                                    face.b(),
                                                    face.c(),
                                                    face.d(),
                                                    face.color());
                                    });
                        if (geometry.lines().isEmpty()) return;
                        Gizmos.addGizmo(
                                        (sink, opacity) -> {
                                            for (var line : geometry.lines())
                                                sink.addLine(
                                                        line.from(), line.to(), line.color(), 2f);
                                        })
                                .setAlwaysOnTop();
                    }
                });
    }

    private static Point nextMark(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            return new Point(pos.getX(), pos.getY(), pos.getZ());
        }
        return AreaHintsClient.point();
    }

    void clear() {
        source = null;
        regions.clear();
    }

    void collect(OutlineFrame frame, RegionTree tree, Editing editing, Point next) {
        if (source != tree) {
            clear();
            if (tree != null) index(tree.root);
            source = tree;
        }
        for (var region : regions) {
            if (editing != null && region.path.equals(editing.original)) continue;
            for (var part : region.parts) part.draw(frame, SAVED);
        }
        if (editing != null) editing.draw(frame, next);
    }

    private void index(RegionTree.Node node) {
        if (node.predicate != null) {
            var parts = node.json.getAsJsonObject("predicate").getAsJsonArray("primitives");
            regions.add(
                    new Region(
                            node.path,
                            parts.asList().stream()
                                    .map(e -> PrimitiveOutline.read(e.getAsJsonObject()))
                                    .toList()));
        }
        node.children.values().forEach(this::index);
    }

    record Editing(
            List<String> original,
            List<PrimitiveOutline> parts,
            int replaced,
            PrimitiveOutline active,
            List<Point> marks,
            boolean marking,
            boolean radial) {
        void draw(OutlineFrame f, Point next) {
            for (int i = 0; i < parts.size(); i++) if (i != replaced) parts.get(i).draw(f, DRAFT);
            if (active != null) active.draw(f, DRAFT);
            if (!marking) return;
            for (var mark : marks) f.mark(mark, .21, DRAFT);
            if (active instanceof PrimitiveOutline.Prism prism && marks.size() == 2)
                guide(f, prism, marks.getFirst(), marks.getLast());
            if (next == null || (radial && marks.size() >= 2)) return;
            f.mark(next, .14, GUIDE);
            if (marks.isEmpty()) return;
            if (active instanceof PrimitiveOutline.Radial shape && shape.radius() == null) {
                var center = marks.getFirst();
                var measured = center.distanceSquared(next, shape.height() != null);
                new PrimitiveOutline.Radial(
                                center, shape.height(), Math.sqrt(measured.doubleValue()))
                        .draw(f, GUIDE);
            } else if (active instanceof PrimitiveOutline.Prism prism) {
                guide(f, prism, marks.getLast(), next);
                if (marks.size() > 1) guide(f, prism, next, marks.getFirst());
            }
        }

        private void guide(OutlineFrame f, PrimitiveOutline.Prism prism, Point a, Point b) {
            var edge = new PrimitiveOutline.Edge(a, b);
            for (double y : PrimitiveOutline.ringHeights(f, prism.height))
                edge.atHeight(f, y, GUIDE);
        }
    }
}
