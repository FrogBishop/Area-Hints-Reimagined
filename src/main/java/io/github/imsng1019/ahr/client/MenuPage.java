package io.github.imsng1019.ahr.client;

import io.github.imsng1019.ahr.data.Detection;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.*;

final class MenuPage extends Screen {
    private interface Row {
        void add(int left, int y, int width);
    }

    private final List<Row> rows = new ArrayList<>();
    private final Runnable back;
    private int page;
    private String error = "";

    MenuPage(Component title, Runnable back) {
        super(title);
        this.back = back;
    }

    static Component tr(String key, Object... args) {
        return Component.translatable("ahr." + key, args);
    }

    void button(String key, Runnable action) {
        button(tr(key), action);
    }

    void button(Component label, Runnable action) {
        rows.add((x, y, w) -> addButton(label, x, y, w, () -> run(action)));
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
        rows.add(
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
                                        .bounds(x, y, w, 20)
                                        .build()));
    }

    private void addButton(Component label, int x, int y, int w, Runnable action) {
        addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, 20).build());
    }

    void field(String key, String initial, Consumer<String> change) {
        String[] value = {initial};
        rows.add(
                (x, y, w) -> {
                    int labelWidth = Math.min(110, w / 3);
                    var label = tr(key);
                    addRenderableOnly(new StringWidget(x, y, labelWidth - 4, 20, label, font));
                    var box = new EditBox(font, x + labelWidth, y, w - labelWidth, 20, label);
                    box.setMaxLength(Integer.MAX_VALUE);
                    box.setValue(value[0]);
                    box.setResponder(
                            s -> {
                                value[0] = s;
                                change.accept(s);
                            });
                    addRenderableWidget(box);
                });
    }

    void interval(double initial, DoubleConsumer change) {
        double[] seconds = {initial};
        rows.add(
                (x, y, w) ->
                        addRenderableWidget(
                                new AbstractSliderButton(
                                        x,
                                        y,
                                        w,
                                        20,
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
        int count = Math.max(1, (height - 78) / 23);
        int pages = Math.max(1, (rows.size() + count - 1) / count);
        page = Math.min(page, pages - 1);
        int w = Math.min(width - 16, 380), x = (width - w) / 2;
        int begin = page * count, end = Math.min(begin + count, rows.size());
        for (int i = begin; i < end; i++) rows.get(i).add(x, 28 + (i - begin) * 23, w);
        if (pages > 1) {
            addButton(
                    Component.literal("‹"),
                    x,
                    height - 26,
                    40,
                    () -> {
                        if (page > 0) {
                            page--;
                            rebuildWidgets();
                        }
                    });
            addButton(
                    Component.literal("›"),
                    x + w - 40,
                    height - 26,
                    40,
                    () -> {
                        if (page + 1 < pages) {
                            page++;
                            rebuildWidgets();
                        }
                    });
        }
        addButton(
                Component.translatable("gui.back"),
                x + w / 2 - 55,
                height - 26,
                110,
                this::onClose);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        g.centeredText(font, title, width / 2, 9, 0xffffffff);
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
        if (back == null) minecraft.gui.setScreen(null);
        else back.run();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
