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
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, height / 2 + 20, 200, 20).build());
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
