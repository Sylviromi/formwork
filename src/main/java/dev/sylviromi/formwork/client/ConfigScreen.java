package dev.sylviromi.formwork.client;

import dev.sylviromi.formwork.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class ConfigScreen extends Screen {
    private final Screen parent;

    ConfigScreen(Screen parent) {
        super(Component.translatable("formwork.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(label(), b -> {
            Config.ANIMATIONS.set(!Config.ANIMATIONS.get());
            Config.SPEC.save();
            b.setMessage(label());
        }).bounds(width / 2 - 100, height / 2 - 10, 200, 20).build());
        addRenderableWidget(speed(Config.ANIMATION_SPEED, "formwork.config.animation_speed", width / 2 - 100, height / 2 + 14));
        addRenderableWidget(speed(Config.DISMANTLE_SPEED, "formwork.config.dismantle_speed", width / 2 - 100, height / 2 + 38));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height / 2 + 70, 200, 20).build());
    }

    private static net.minecraft.client.gui.components.AbstractSliderButton speed(net.minecraftforge.common.ForgeConfigSpec.DoubleValue v, String key, int x, int y) {
        return new net.minecraft.client.gui.components.AbstractSliderButton(x, y, 200, 20, Component.empty(), (Math.log(v.get()) / Math.log(2) + 2) / 4) {
            { updateMessage(); }
            private double value() { return Math.pow(2, this.value * 4 - 2); }
            @Override protected void updateMessage() { setMessage(Component.translatable(key, String.format("%.2fx", value()))); }
            @Override protected void applyValue() { v.set(Math.round(value() * 20) / 20.0); Config.SPEC.save(); }
        };
    }

    private static Component label() {
        return Component.translatable("formwork.config.animations", CommonComponents.optionStatus(Config.ANIMATIONS.get()));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
