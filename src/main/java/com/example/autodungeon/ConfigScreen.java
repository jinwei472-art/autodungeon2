package com.example.autodungeon;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** Settings screen (press K in game). Two columns of buttons; numbers use - / + buttons. */
public class ConfigScreen extends Screen {
    private int slot = 0;

    public ConfigScreen() {
        super(Text.literal("AutoDungeon 设置"));
    }

    @Override
    protected void init() {
        slot = 0;

        toggle("自动模式",
                () -> AutoDungeonClient.instance != null && AutoDungeonClient.instance.isEnabled(),
                v -> { if (AutoDungeonClient.instance != null) AutoDungeonClient.instance.setEnabled(client, v); });
        toggle("持续右键", () -> Config.holdUse, v -> Config.holdUse = v);

        toggle("仅怪物附近右键", () -> Config.useOnlyWhenMobs, v -> Config.useOnlyWhenMobs = v);
        number("技能触发距离", () -> Config.skillRange, v -> Config.skillRange = v, 0.5, 1, 20);

        number("怪物搜索范围", () -> Config.scanRange, v -> Config.scanRange = v, 1, 2, 40);
        number("靠近距离", () -> Config.approachDist, v -> Config.approachDist = v, 0.5, 1, 6);

        toggle("限制活动区域", () -> Config.limitArea, v -> Config.limitArea = v);
        number("活动半径", () -> Config.areaRadius, v -> Config.areaRadius = v, 1, 3, 60);

        action("设当前位置为中心", () -> {
            ClientPlayerEntity p = client == null ? null : client.player;
            if (p != null) {
                Config.areaX = p.getX();
                Config.areaY = p.getY();
                Config.areaZ = p.getZ();
                Config.save();
            }
        });
        toggle("自动捡物", () -> Config.autoLoot, v -> Config.autoLoot = v);

        number("捡物范围", () -> Config.lootRange, v -> Config.lootRange = v, 1, 1, 20);
        toggle("内置攻击", () -> Config.builtinAttack, v -> Config.builtinAttack = v);

        toggle("自动转向", () -> Config.autoFace, v -> Config.autoFace = v);
        toggle("后台运行", () -> Config.background, v -> Config.background = v);

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), b -> close())
                .dimensions(width / 2 - 100, height - 26, 200, 20).build());
    }

    // ---------- layout helpers ----------

    private int colX() { return width / 2 - 155 + (slot % 2) * 160; }
    private int rowY() { return 34 + (slot / 2) * 22; }

    private void toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
        int x = colX(), y = rowY();
        slot++;
        final ButtonWidget[] ref = new ButtonWidget[1];
        ref[0] = ButtonWidget.builder(label(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            Config.save();
            ref[0].setMessage(label(name, get.getAsBoolean()));
        }).dimensions(x, y, 150, 20).build();
        addDrawableChild(ref[0]);
    }

    private void number(String name, DoubleSupplier get, DoubleConsumer set, double step, double min, double max) {
        int x = colX(), y = rowY();
        slot++;
        ButtonWidget display = ButtonWidget.builder(label(name, get.getAsDouble()), b -> {})
                .dimensions(x, y, 106, 20).build();
        addDrawableChild(display);
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
            set.accept(clamp(get.getAsDouble() - step, min, max));
            Config.save();
            display.setMessage(label(name, get.getAsDouble()));
        }).dimensions(x + 108, y, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
            set.accept(clamp(get.getAsDouble() + step, min, max));
            Config.save();
            display.setMessage(label(name, get.getAsDouble()));
        }).dimensions(x + 130, y, 20, 20).build());
    }

    private void action(String name, Runnable r) {
        int x = colX(), y = rowY();
        slot++;
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> r.run())
                .dimensions(x, y, 150, 20).build());
    }

    private static double clamp(double v, double min, double max) {
        double r = Math.round(v * 10.0) / 10.0;
        return Math.max(min, Math.min(max, r));
    }

    private static Text label(String name, boolean on) {
        return Text.literal(name + ": " + (on ? "开" : "关"));
    }

    private static Text label(String name, double v) {
        return Text.literal(String.format("%s: %.1f", name, v));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawTextWithShadow(textRenderer, title, width / 2 - textRenderer.getWidth(title) / 2, 8, 0xFFFFFFFF);
        String c = String.format("活动中心: %.0f, %.0f, %.0f", Config.areaX, Config.areaY, Config.areaZ);
        ctx.drawTextWithShadow(textRenderer, Text.literal(c), width / 2 - textRenderer.getWidth(c) / 2, 20, 0xFFAAAAAA);
    }
}
