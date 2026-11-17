package dev.sylviromi.mbplacer.client;

import net.minecraft.client.Minecraft;

/** Client-only entry points called from common code through DistExecutor. */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreen(new PlacerScreen());
    }
}
