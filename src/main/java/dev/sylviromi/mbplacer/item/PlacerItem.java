package dev.sylviromi.mbplacer.item;

import dev.sylviromi.mbplacer.build.Dismantler;
import dev.sylviromi.mbplacer.build.Placer;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** The tool. Sneak + use opens the picker, use on a block places the selected structure. It has no inventory. */
public class PlacerItem extends Item {
    private static final String SELECTION = "selection";
    /** Written by AE2 when the tool is linked in a Wireless Access Point (see AeLink). */
    public static final String AE_LINK = "accessPoint";

    public PlacerItem() {
        super(new Properties().stacksTo(1));
    }

    public static Selection selection(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().contains(SELECTION) ? Selection.load(stack.getTag().getCompound(SELECTION)) : Selection.EMPTY;
    }

    public static void setSelection(ItemStack stack, Selection sel) {
        stack.getOrCreateTag().put(SELECTION, sel.save());
    }

    /** The block the structure's bottom centre goes to when the player uses the tool on this hit. */
    public static BlockPos target(Level level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        return level.getBlockState(pos).canBeReplaced() ? pos : pos.relative(hit.getDirection());
    }

    // ---- placement settings: anchor, offset, rotation, mode. Same tag on both sides; the client sends it with Net.SetPlacement ----
    public static final String ANCHOR = "anchor", ANCHOR_FACING = "anchorFacing", OFFSET = "offset", ROTATION = "rotation", DISMANTLE = "dismantle";
    public static final int MAX_OFFSET = 64, MAX_ANCHOR_DISTANCE = 128;

    public record Placement(BlockPos target, Direction facing) {
    }

    /** Where the structure goes: the anchor if set, else the looked-at block; then rotation and offset. Null when there is nothing to aim at. */
    @Nullable
    public static Placement placement(ItemStack tool, Level level, @Nullable BlockHitResult hit, Direction playerFacing) {
        BlockPos base = anchor(tool);
        Direction facing = playerFacing;
        if (base != null) facing = Direction.from2DDataValue(tool.getTag().getInt(ANCHOR_FACING));
        else if (hit != null && hit.getType() == HitResult.Type.BLOCK) base = target(level, hit);
        else return null;
        for (int i = 0; i < rotation(tool); i++) facing = facing.getClockWise();
        int[] o = offset(tool);
        return new Placement(base.offset(o[0], o[1], o[2]), facing);
    }

    @Nullable
    public static BlockPos anchor(ItemStack tool) {
        return tool.getTag() != null && tool.getTag().contains(ANCHOR) ? BlockPos.of(tool.getTag().getLong(ANCHOR)) : null;
    }

    public static int[] offset(ItemStack tool) {
        int[] o = tool.getTag() == null ? new int[0] : tool.getTag().getIntArray(OFFSET);
        return o.length == 3 ? o : new int[3];
    }

    public static int rotation(ItemStack tool) {
        return tool.getTag() == null ? 0 : tool.getTag().getInt(ROTATION);
    }

    public static boolean dismantle(ItemStack tool) {
        return tool.getTag() != null && tool.getTag().getBoolean(DISMANTLE);
    }

    /** The placement settings as one tag (what the client edits and sends). */
    public static CompoundTag placementTag(ItemStack tool) {
        CompoundTag out = new CompoundTag();
        BlockPos a = anchor(tool);
        if (a != null) {
            out.putLong(ANCHOR, a.asLong());
            out.putInt(ANCHOR_FACING, tool.getTag().getInt(ANCHOR_FACING));
        }
        out.putIntArray(OFFSET, offset(tool));
        out.putInt(ROTATION, rotation(tool));
        out.putBoolean(DISMANTLE, dismantle(tool));
        return out;
    }

    public static CompoundTag withAnchor(CompoundTag in, @Nullable Placement at) {
        CompoundTag out = in.copy();
        out.remove(ANCHOR);
        if (at != null) {
            out.putLong(ANCHOR, at.target().asLong());
            out.putInt(ANCHOR_FACING, at.facing().get2DDataValue());
        }
        out.putIntArray(OFFSET, new int[3]);
        out.putInt(ROTATION, 0);
        return out;
    }

    /** Validates and stores settings sent by the client: offsets clamped, anchors only near the player. */
    public static void applyPlacement(ItemStack tool, CompoundTag in, BlockPos playerPos) {
        CompoundTag t = tool.getOrCreateTag();
        t.remove(ANCHOR);
        if (in.contains(ANCHOR) && BlockPos.of(in.getLong(ANCHOR)).closerThan(playerPos, MAX_ANCHOR_DISTANCE)) {
            t.putLong(ANCHOR, in.getLong(ANCHOR));
            t.putInt(ANCHOR_FACING, Math.floorMod(in.getInt(ANCHOR_FACING), 4));
        }
        int[] o = in.getIntArray(OFFSET);
        int[] clamped = new int[3];
        for (int i = 0; i < 3 && o.length == 3; i++) clamped[i] = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, o[i]));
        t.putIntArray(OFFSET, clamped);
        t.putInt(ROTATION, Math.floorMod(in.getInt(ROTATION), 4));
        t.putBoolean(DISMANTLE, in.getBoolean(DISMANTLE));
    }

    /** Places or dismantles at the current placement. */
    private static void act(ServerPlayer player, ItemStack tool, @Nullable BlockHitResult hit) {
        Placement p = placement(tool, player.level(), hit, player.getDirection());
        if (p == null) return;
        if (!p.target().closerThan(player.blockPosition(), MAX_ANCHOR_DISTANCE)) {
            player.displayClientMessage(Component.translatable("mbplacer.msg.too_far").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (dismantle(tool)) Dismantler.dismantle(player, tool, p.target(), p.facing());
        else Placer.place(player, tool, p.target(), p.facing());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack tool = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) openScreen();
            return InteractionResultHolder.sidedSuccess(tool, level.isClientSide);
        }
        if (anchor(tool) == null) return InteractionResultHolder.pass(tool);
        // anchored: works without looking at a block
        if (player instanceof ServerPlayer sp) act(sp, tool, null);
        return InteractionResultHolder.sidedSuccess(tool, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (ctx.getLevel().isClientSide) openScreen();
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        if (player instanceof ServerPlayer sp)
            act(sp, ctx.getItemInHand(), new BlockHitResult(ctx.getClickLocation(), ctx.getClickedFace(), ctx.getClickedPos(), ctx.isInside()));
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    private static void openScreen() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> dev.sylviromi.mbplacer.client.ClientHooks::openScreen);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Selection sel = selection(stack);
        if (!sel.isEmpty()) tooltip.add(Component.translatable("mbplacer.tooltip.selected", displayName(sel.entry())).withStyle(ChatFormatting.AQUA));
        boolean linked = stack.getTag() != null && stack.getTag().contains(AE_LINK);
        tooltip.add(Component.translatable(linked ? "mbplacer.tooltip.linked" : "mbplacer.tooltip.unlinked").withStyle(ChatFormatting.GRAY));
        if (anchor(stack) != null) tooltip.add(Component.translatable("mbplacer.tooltip.anchored").withStyle(ChatFormatting.YELLOW));
        if (dismantle(stack)) tooltip.add(Component.translatable("mbplacer.tooltip.dismantle").withStyle(ChatFormatting.RED));
        tooltip.add(Component.translatable("mbplacer.tooltip.usage").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Name of a blueprint or generator: our own lang key first, then the legacy mbtool key, then the raw name. */
    public static Component displayName(String id) {
        String name = Catalog.shortName(id);
        List<String> keys = id.startsWith("mbplacer:gen/") ? List.of("mbplacer.gen." + name) : List.of("mbplacer.structure." + name, "mbtool.structure." + name);
        for (String key : keys)
            if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(prettify(name));
    }

    public static String prettify(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("[_\\-]+")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
