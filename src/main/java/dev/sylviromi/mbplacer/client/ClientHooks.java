package dev.sylviromi.mbplacer.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sylviromi.mbplacer.MbPlacer;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.Net;
import dev.sylviromi.mbplacer.build.Placements;
import dev.sylviromi.mbplacer.build.Resolver;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Client-only entry points: screens, key bindings and placement edits. */
@Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT)
public final class ClientHooks {
    static final KeyMapping PLACEMENT = new KeyMapping("key.mbplacer.placement", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.mbplacer");

    private ClientHooks() {
    }

    /** Pointing at a placed multiblock: edit it. Else the editor for the tool's multiblock, or the browser when nothing (known) is selected. */
    public static void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        Placements.Rec placed = PlacerItem.placementLookedAt(mc.player);
        if (placed != null) {
            String placedId = Selection.load(placed.selection()).entry();
            var entry = Catalog.client.entries().stream().filter(e -> e.members().contains(placedId)).findFirst();
            if (entry.isPresent()) {
                mc.setScreen(new EditorScreen(entry.get(), placed));
                return;
            }
        }
        String current = PlacerItem.selection(WorldGhost.heldTool()).entry();
        Minecraft.getInstance().setScreen(Catalog.client.entries().stream().filter(e -> e.members().contains(current)).findFirst()
                .<net.minecraft.client.gui.screens.Screen>map(EditorScreen::new).orElseGet(BrowserScreen::new));
    }

    @Mod.EventBusSubscriber(modid = MbPlacer.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    static final class ModEvents {
        @SubscribeEvent
        static void keys(RegisterKeyMappingsEvent e) {
            e.register(PLACEMENT);
        }
    }

    /** After a hold-to-dismantle ends, the still-held use key must not go on to place something: ignored until released. */
    private static boolean dismantling, holdLock;

    @SubscribeEvent
    static void onUseKey(InputEvent.InteractionKeyMappingTriggered e) {
        if (holdLock && e.isUseItem()) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (e.phase != TickEvent.Phase.END || mc.player == null) return;
        boolean using = mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof PlacerItem;
        if (dismantling && !using) holdLock = true;
        dismantling = using;
        if (holdLock && !mc.options.keyUse.isDown()) holdLock = false;
        updateAim(mc);
        if (mc.screen != null) return;
        ItemStack tool = WorldGhost.heldTool();
        while (PLACEMENT.consumeClick()) if (!tool.isEmpty()) mc.setScreen(new PlacementScreen());
    }

    private static String footprintKey = "";
    private static Resolver.Footprint footprint;

    /** The facing used for placing: follows the view, but only switches once clearly past a diagonal (no flicker at 45 degrees). */
    static Direction facing = Direction.NORTH;
    /** Ctrl held: blocked spots are skipped when placing. */
    static boolean skipBlocked;
    private static Net.Aim sent;

    private static void updateAim(Minecraft mc) {
        float yaw = mc.player.getYRot();
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - facing.toYRot()));
        if (off > 55) facing = Direction.fromYRot(yaw);
        long window = mc.getWindow().getWindow();
        skipBlocked = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        Net.Aim now = new Net.Aim(facing, skipBlocked);
        if (!now.equals(sent) && !WorldGhost.heldTool().isEmpty()) {
            sent = now;
            Net.CHANNEL.sendToServer(now);
        }
    }

    /** Where the held tool would place right now (aiming with the long placement ray), or null. */
    @Nullable
    static PlacerItem.Placement currentPlacement(ItemStack tool) {
        Minecraft mc = Minecraft.getInstance();
        Selection sel = PlacerItem.selection(tool);
        String key = sel.save() + "|" + System.identityHashCode(Catalog.client) + Catalog.client.blueprints.size();
        if (!key.equals(footprintKey)) {
            footprintKey = key;
            var specs = Resolver.specs(Catalog.client, sel);
            footprint = specs == null || specs.isEmpty() ? null : Resolver.Footprint.of(specs.keySet());
        }
        if (footprint == null) return null;
        return PlacerItem.placement(tool, mc.level, PlacerItem.aim(mc.player, mc.getFrameTime()), facing, footprint);
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
