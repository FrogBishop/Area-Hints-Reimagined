package io.github.imsng1019.ahr.data;

import io.github.imsng1019.ahr.geometry.Point;

import java.util.*;

public final class Detection {
    private double ticks;
    private Set<List<String>> previous = Set.of();

    public static double seconds(double position) {
        return Math.pow(2, 8 * position - 4);
    }

    public static double position(double seconds) {
        return (Math.log(seconds) / Math.log(2) + 4) / 8;
    }

    public boolean advance(double seconds) {
        double period = 20 * seconds;
        ticks++;
        if (ticks < period) return false;
        ticks %= period;
        return true;
    }

    public void reset() {
        ticks = 0;
        previous = Set.of();
    }

    public RegionTree.Node sample(RegionTree tree, Point point, boolean showOnExit) {
        var eligible = tree.eligible(point);
        Set<List<String>> current = new HashSet<>(), candidates = new HashSet<>();
        for (var node : eligible) {
            current.add(node.path);
            if (!previous.contains(node.path)) candidates.add(node.path);
        }
        if (showOnExit) {
            for (var exit : previous) {
                if (current.contains(exit)) continue;
                for (int i = exit.size() - 1; i >= 0; i--) {
                    var parent = exit.subList(0, i);
                    if (current.contains(parent)) {
                        candidates.add(parent);
                        break;
                    }
                }
            }
        }
        previous = current;
        RegionTree.Node best = null;
        for (var n : eligible)
            if (candidates.contains(n.path) && (best == null || n.path.size() > best.path.size()))
                best = n;
        return best;
    }
}
