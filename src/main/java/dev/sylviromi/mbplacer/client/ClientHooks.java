package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.Net;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Client-only entry points: screens, key bindings and placement edits. */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class ClientHooks {
    static final KeyMapping ANCHOR = new KeyMapping("key.mbplacer.anchor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.mbplacer");
    static final KeyMapping PLACEMENT = new KeyMapping("key.mbplacer.placement", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.mbplacer");

    private ClientHooks() {
    }

    public static void openScreen() {
        Minecraft.getInstance().setScreen(new PlacerScreen());
    }

    @Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    static final class ModEvents {
        @SubscribeEvent
        static void keys(RegisterKeyMappingsEvent e) {
            e.register(ANCHOR);
            e.register(PLACEMENT);
        }
    }

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.phase != TickEvent.Phase.END || mc.player == null || mc.screen != null) return;
        ItemStack tool = WorldGhost.heldTool();
        while (ANCHOR.consumeClick()) if (!tool.isEmpty()) toggleAnchor(tool);
        while (PLACEMENT.consumeClick()) if (!tool.isEmpty()) mc.setScreen(new PlacementScreen());
    }

    /** Where the held tool would place right now. */
    @Nullable
    static PlacerItem.Placement currentPlacement(ItemStack tool) {
        Minecraft mc = Minecraft.getInstance();
        return PlacerItem.placement(tool, mc.level, mc.hitResult instanceof BlockHitResult b ? b : null, mc.player.getDirection());
    }

    /** Anchors at the current placement (folding offset and rotation into it), or releases the anchor. */
    static void toggleAnchor(ItemStack tool) {
        PlacerItem.Placement at = PlacerItem.anchor(tool) == null ? currentPlacement(tool) : null;
        setPlacement(tool, PlacerItem.withAnchor(PlacerItem.placementTag(tool), at));
        Minecraft.getInstance().player.displayClientMessage(Component.translatable(at == null ? "mbplacer.msg.anchor_released" : "mbplacer.msg.anchored"), true);
    }

    /** Applies the settings locally at once (the ghost moves without waiting) and sends them to the server. */
    static void setPlacement(ItemStack tool, CompoundTag placement) {
        PlacerItem.applyPlacement(tool, placement, Minecraft.getInstance().player.blockPosition());
        Net.CHANNEL.sendToServer(new Net.SetPlacement(placement));
    }
}
