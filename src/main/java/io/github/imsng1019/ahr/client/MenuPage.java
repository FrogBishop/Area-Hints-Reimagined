package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.data.Detection;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.*;

final class MenuPage extends Screen {
    private static final int CONTROL_HEIGHT = 20;
    private static final int COLUMN_GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int LABEL_GAP = 2;
    private static final int CONTENT_TOP = 28;

    private interface Control {
        void add(int left, int y, int width);
    }

    private record Cell(Component label, Control control) {}

    private final List<Cell> cells = new ArrayList<>();
    private final Runnable back;
    private Consumer<MenuPage> contents;
    private int page;
    private String error = "";
    private boolean waiting;
    private Component reveal;

    MenuPage(Component title, Runnable back) {
        super(title);
        this.back = back;
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("ahr." + key, args);
    }

    void contents(Consumer<MenuPage> contents) {
        this.contents = contents;
    }

    void show() {
        minecraft.gui.setScreen(this);
    }

    void showField(Component label) {
        reveal = label;
        show();
    }

    void refresh() {
        rebuildWidgets();
    }

    void startRow() {
        if ((cells.size() & 1) != 0) control((x, y, w) -> {});
    }

    void button(String key, Runnable action) {
        button(tr(key), action);
    }

    void button(Component label, Runnable action) {
        control((x, y, w) -> addButton(label, x, y, w, () -> run(action)));
    }

    private void control(Control widget) {
        cells.add(new Cell(null, widget));
    }

    void toggle(String key, Boolean initial, boolean inherit, Consumer<Boolean> change) {
        Boolean[] selected = {initial};
        Supplier<Component> label =
                () ->
                        tr(key).copy()
                                .append("  ")
                                .append(
                                        selected[0] == null
                                                ? tr("inherit")
                                                : Component.translatable(
                                                        selected[0]
                                                                ? "options.on"
                                                                : "options.off"));
        control(
                (x, y, w) ->
                        addRenderableWidget(
                                Button.builder(
                                                label.get(),
                                                button -> {
                                                    if (selected[0] == null) selected[0] = true;
                                                    else if (selected[0]) selected[0] = false;
                                                    else
                                                        selected[0] = inherit ? null : Boolean.TRUE;
                                                    change.accept(selected[0]);
                                                    button.setMessage(label.get());
                                                })
                                        .bounds(x, y, w, CONTROL_HEIGHT)
                                        .build()));
    }

    private Button addButton(Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(
                Button.builder(label, b -> action.run()).bounds(x, y, w, CONTROL_HEIGHT).build());
    }

    void field(String key, String initial, Consumer<String> change) {
        field(tr(key), initial, null, change);
    }

    void field(Component label, String initial, Component hint, Consumer<String> change) {
        String[] value = {initial};
        cells.add(
                new Cell(
                        label,
                        (x, y, w) -> {
                            var box = new EditBox(font, x, y, w, CONTROL_HEIGHT, label);
                            box.setMaxLength(Integer.MAX_VALUE);
                            box.setValue(value[0]);
                            if (hint != null) box.setHint(hint);
                            box.setResponder(
                                    s -> {
                                        value[0] = s;
                                        change.accept(s);
                                    });
                            addRenderableWidget(box);
                        }));
    }

    void interval(double initial, DoubleConsumer change) {
        double[] seconds = {initial};
        control(
                (x, y, w) ->
                        addRenderableWidget(
                                new AbstractSliderButton(
                                        x,
                                        y,
                                        w,
                                        CONTROL_HEIGHT,
                                        tr(
                                                "interval",
                                                String.format(Locale.ROOT, "%.4f", seconds[0])),
                                        Detection.position(seconds[0])) {
                                    protected void updateMessage() {
                                        setMessage(
                                                tr(
                                                        "interval",
                                                        String.format(
                                                                Locale.ROOT,
                                                                "%.4f",
                                                                Detection.seconds(value))));
                                    }

                                    protected void applyValue() {
                                        seconds[0] = Detection.seconds(value);
                                        change.accept(seconds[0]);
                                    }
                                }));
    }

    private void run(Runnable action) {
        try {
            error = "";
            action.run();
        } catch (RuntimeException e) {
            error = Objects.toString(e.getMessage(), "Invalid value");
        }
    }

    @Override
    protected void init() {
        if (contents != null) {
            cells.clear();
            contents.accept(this);
        }
        var starts = pageStarts();
        page = Math.min(page, starts.size() - 1);
        if (reveal != null) {
            for (int i = 0; i < cells.size(); i++)
                if (reveal.equals(cells.get(i).label)) {
                    int at = Collections.binarySearch(starts, i);
                    page = at >= 0 ? at : -at - 2;
                    break;
                }
        }
        int cellWidth = Math.clamp((width - 16 - COLUMN_GAP) / 2, 1, 210);
        int gridWidth = 2 * cellWidth + COLUMN_GAP;
        int left = (width - gridWidth) / 2;
        int end = page + 1 < starts.size() ? starts.get(page + 1) : cells.size();
        int y = CONTENT_TOP;
        for (int first = starts.get(page); first < end; first += 2) {
            int controlY = y + labelSpace(first);
            for (int i = first; i < Math.min(first + 2, end); i++) {
                var cell = cells.get(i);
                int x = left + (i - first) * (cellWidth + COLUMN_GAP);
                if (cell.label != null) {
                    var caption =
                            new StringWidget(
                                    x,
                                    y,
                                    Math.min(cellWidth, font.width(cell.label)),
                                    font.lineHeight,
                                    cell.label,
                                    font);
                    addRenderableOnly(caption.setMaxWidth(cellWidth));
                }
                cell.control.add(x, controlY, cellWidth);
            }
            y = controlY + CONTROL_HEIGHT + ROW_GAP;
        }
        if (starts.size() > 1) {
            int arrowWidth = Math.min(40, Math.max(1, (gridWidth - cellWidth) / 2 - COLUMN_GAP));
            addButton(
                                    Component.literal("‹"),
                                    left,
                                    height - 26,
                                    arrowWidth,
                                    () -> {
                                        if (page > 0) {
                                            page--;
                                            rebuildWidgets();
                                        }
                                    })
                            .active =
                    page > 0;
            addButton(
                                    Component.literal("›"),
                                    left + gridWidth - arrowWidth,
                                    height - 26,
                                    arrowWidth,
                                    () -> {
                                        if (page + 1 < starts.size()) {
                                            page++;
                                            rebuildWidgets();
                                        }
                                    })
                            .active =
                    page + 1 < starts.size();
        }
        addButton(
                Component.translatable("gui.back"),
                (width - cellWidth) / 2,
                height - 26,
                cellWidth,
                this::onClose);
        if (reveal != null) {
            for (var child : children())
                if (child instanceof EditBox box && reveal.equals(box.getMessage())) {
                    setInitialFocus(box);
                    break;
                }
            reveal = null;
        }
        if (waiting)
            for (var child : children())
                if (child instanceof AbstractWidget widget) widget.active = false;
    }

    void waitingForSave() {
        waiting = true;
        error = "";
        refresh();
    }

    void saveFailed(String reason) {
        waiting = false;
        error = reason;
        refresh();
    }

    private int labelSpace(int first) {
        boolean labeled =
                cells.get(first).label != null
                        || (first + 1 < cells.size() && cells.get(first + 1).label != null);
        return labeled ? font.lineHeight + LABEL_GAP : 0;
    }

    private List<Integer> pageStarts() {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        int y = CONTENT_TOP;
        for (int first = 0; first < cells.size(); first += 2) {
            int rowHeight = labelSpace(first) + CONTROL_HEIGHT;
            if (first > starts.getLast() && y + rowHeight > height - 46) {
                starts.add(first);
                y = CONTENT_TOP;
            }
            y += rowHeight + ROW_GAP;
        }
        return starts;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        g.centeredText(font, title, width / 2, 9, 0xffffffff);
        if (waiting) g.centeredText(font, tr("savingSettings"), width / 2, height - 40, 0xffffffff);
        if (!error.isEmpty())
            g.centeredText(
                    font,
                    font.plainSubstrByWidth(error, width - 12),
                    width / 2,
                    height - 40,
                    0xffff8888);
    }

    @Override
    public void onClose() {
        if (waiting) return;
        if (back == null) minecraft.gui.setScreen(null);
        else run(back);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
