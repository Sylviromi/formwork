package dev.sylviromi.formwork;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ANIMATIONS;
    public static final ForgeConfigSpec.DoubleValue ANIMATION_SPEED, DISMANTLE_SPEED;

    static {
        var b = new ForgeConfigSpec.Builder();
        ANIMATIONS = b.comment("Play the sweep when a structure is placed or dismantled. Off: blocks appear and vanish at once.").define("animations", true);
        ANIMATION_SPEED = b.comment("How fast the placement and dismantle sweep runs. 2 is twice as fast.").defineInRange("animation_speed", 1.0, 0.25, 4.0);
        DISMANTLE_SPEED = b.comment("How fast holding use dismantles or updates a placed structure. 2 is twice as fast.").defineInRange("dismantle_speed", 1.0, 0.25, 4.0);
        SPEC = b.build();
    }

    private Config() {
    }
}
