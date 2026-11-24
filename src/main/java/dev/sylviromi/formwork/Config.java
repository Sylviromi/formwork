package dev.sylviromi.formwork;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ANIMATIONS;

    static {
        var b = new ForgeConfigSpec.Builder();
        ANIMATIONS = b.comment("Play the sweep when a structure is placed or dismantled. Off: blocks appear and vanish at once.").define("animations", true);
        SPEC = b.build();
    }

    private Config() {
    }
}
