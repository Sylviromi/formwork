package dev.sylviromi.formwork.item;

import dev.sylviromi.formwork.build.Dismantler;
import dev.sylviromi.formwork.build.Placements;
import dev.sylviromi.formwork.build.Placer;
import dev.sylviromi.formwork.build.Updater;
import dev.sylviromi.formwork.build.Resolver;
import dev.sylviromi.formwork.net.ClientData;
import dev.sylviromi.formwork.net.Net;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.UseAnim;
import dev.sylviromi.formwork.structure.Catalog;
import dev.sylviromi.formwork.structure.Selection;
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
    public static final String ANCHOR = "anchor", ANCHOR_FACING = "anchorFacing", OFFSET = "offset", ROTATION = "rotation", MIRROR = "mirror", NO_ANIMATION = "noAnimation";
    public static final int MAX_OFFSET = 64, MAX_ANCHOR_DISTANCE = 128;

    public record Placement(BlockPos target, Direction facing, boolean mirror) {
    }

    /** How far the tool aims when placing (dismantling and editing too). */
    public static final double PLACE_RANGE = 64;

    /** The block the tool aims at for placing, up to PLACE_RANGE away. */
    @Nullable
    public static BlockHitResult aim(Player player, float partial) {
        return player.pick(PLACE_RANGE, partial, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * Where the structure goes (its bottom centre and facing): the anchor if set, else in front of the aimed-at block, extending
     * away from the player; then the turn (around the centre) and the offset. Null when there is nothing to aim at.
     */
    @Nullable
    public static Placement placement(ItemStack tool, Level level, @Nullable BlockHitResult hit, Direction look, Resolver.Footprint fp) {
        BlockPos base = anchor(tool);
        // the structure's turn never follows the camera: north, or the anchor's, plus the card's rotation
        Direction facing = base != null ? Direction.from2DDataValue(tool.getTag().getInt(ANCHOR_FACING)) : Direction.NORTH;
        for (int i = 0; i < rotation(tool); i++) facing = facing.getClockWise();
        if (base == null && hit == null) return null;
        if (base == null) base = Resolver.centerFor(fp, target(level, hit), look, facing); // the look only decides which side it extends to
        int[] o = offset(tool);
        return new Placement(base.offset(o[0], o[1], o[2]), facing, mirror(tool));
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

    public static boolean mirror(ItemStack tool) {
        return tool.getTag() != null && tool.getTag().getBoolean(MIRROR);
    }

    public static boolean animate(ItemStack tool) {
        return tool.getTag() == null || !tool.getTag().getBoolean(NO_ANIMATION);
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
        out.putBoolean(MIRROR, mirror(tool));
        out.putBoolean(NO_ANIMATION, !animate(tool));
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
        t.putBoolean(MIRROR, in.getBoolean(MIRROR));
        t.putBoolean(NO_ANIMATION, in.getBoolean(NO_ANIMATION));
    }

    /** Places at the current placement; aims with the long ray like the client's ghost. */
    private static void act(ServerPlayer player, ItemStack tool) {
        var specs = Resolver.specs(Catalog.server, selection(tool));
        if (specs == null || specs.isEmpty()) {
            player.displayClientMessage(Component.translatable("formwork.msg.nothing_selected").withStyle(ChatFormatting.RED), true);
            return;
        }
        Net.Aim a = Net.aim(player); // the facing the client's ghost showed, and whether Ctrl was held
        Placement p = placement(tool, player.level(), aim(player, 1), a.facing(), Resolver.Footprint.of(specs.keySet()));
        if (p == null) return;
        if (!p.target().closerThan(player.blockPosition(), MAX_ANCHOR_DISTANCE)) {
            player.displayClientMessage(Component.translatable("formwork.msg.too_far").withStyle(ChatFormatting.RED), true);
            return;
        }
        Placer.place(player, tool, p.target(), p.facing(), p.mirror(), a.skipBlocked());
    }

    // ---- dismantle: hold use on a placed multiblock, like breaking a block ----

    /** The placement whose block is under the crosshair (not air or a foreign block inside its bounds). Each side uses its own records. */
    @Nullable
    public static Placements.Rec placementLookedAt(Player player) {
        boolean server = player.level() instanceof ServerLevel;
        var recs = server ? Placements.of((ServerLevel) player.level()).all() : ClientData.placements;
        if (recs.isEmpty() || !(player.pick(PLACE_RANGE, 1, false) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        Placements.Rec rec = Placements.at(recs, hit.getBlockPos());
        return rec != null && Placements.positions(rec, Catalog.side(!server)).contains(hit.getBlockPos()) ? rec : null;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof ServerPlayer sp)) return;
        Placements.Rec rec = placementLookedAt(sp);
        if (rec == null) {
            sp.stopUsingItem(); // looked away: cancelled
            return;
        }
        if (getUseDuration(stack) - remaining >= Placements.dismantleTicks(rec)) {
            Dismantler.dismantle(sp, stack, rec);
            sp.stopUsingItem();
            // the use key is usually still down: don't let it place something right away (the client also waits for release)
            sp.getCooldowns().addCooldown(this, 10);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack tool = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) openScreen();
            return InteractionResultHolder.sidedSuccess(tool, level.isClientSide);
        }
        // an update is waiting: use cancels it
        if (level.isClientSide ? ClientData.progressFor != null : Updater.cancel((ServerPlayer) player))
            return InteractionResultHolder.sidedSuccess(tool, level.isClientSide);
        // pointing at a placed multiblock: hold to dismantle it
        if (placementLookedAt(player) != null) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(tool);
        }
        // anchored, or aiming at a block further away than reach
        if (anchor(tool) == null && aim(player, 1) == null) return InteractionResultHolder.pass(tool);
        if (player instanceof ServerPlayer sp) act(sp, tool);
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
        if (placementLookedAt(player) != null) return InteractionResult.PASS; // use() handles it: hold to dismantle
        if (ctx.getLevel().isClientSide ? ClientData.progressFor != null : Updater.waiting((ServerPlayer) player)) return InteractionResult.PASS; // use() cancels the update
        if (player instanceof ServerPlayer sp)
            act(sp, ctx.getItemInHand());
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }

    private static void openScreen() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> dev.sylviromi.formwork.client.ClientHooks::openScreen);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        Selection sel = selection(stack);
        if (!sel.isEmpty()) tooltip.add(Component.translatable("formwork.tooltip.selected", displayName(sel.entry())).withStyle(ChatFormatting.AQUA));
        boolean linked = stack.getTag() != null && stack.getTag().contains(AE_LINK);
        if (dev.sylviromi.formwork.Formwork.AE2)
            tooltip.add((linked ? Component.translatable("formwork.tooltip.linked")
                    : Component.translatable("formwork.tooltip.unlinked", Component.translatable("block.ae2.wireless_access_point"))).withStyle(ChatFormatting.GRAY));
        if (anchor(stack) != null) tooltip.add(Component.translatable("formwork.tooltip.anchored").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("formwork.tooltip.usage").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("formwork.tooltip.placed").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("formwork.tooltip.card").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * Name of a list entry. A tier group without its own lang key is named by the words its members' names share,
     * minus a trailing "Tier": "VoidMiner Tier 1 Rubetine" .. "VoidMiner Tier 9 Ultimate" -> "VoidMiner".
     */
    public static Component entryName(Catalog.Entry e) {
        if (e.members().size() <= 1 || net.minecraft.locale.Language.getInstance().has("formwork.structure." + Catalog.shortName(e.key())))
            return displayName(e.members().size() > 1 ? e.key() : e.members().get(0));
        List<String> shared = new java.util.ArrayList<>(Catalog.sharedWords(e.members().stream()
                .map(m -> List.of(displayName(m).getString().split(" "))).toList()));
        while (!shared.isEmpty() && shared.get(shared.size() - 1).toLowerCase(java.util.Locale.ROOT).matches("tier.*|\\d+|-")) shared.remove(shared.size() - 1);
        return shared.isEmpty() ? displayName(e.key()) : Component.literal(String.join(" ", shared));
    }

    /**
     * Name of a blueprint or generator. Generators: the owning mod's own name (e.g. Mekanism's "Industrial Turbine").
     * Blueprints: our lang key (formwork.structure.<file>), then Multi Builder Tool's (mbtool.structure.<file>, read with its
     * structure folder for now, see CatalogLoader.LEGACY), else the prettified file name.
     */
    public static Component displayName(String id) {
        String own = Catalog.generators().stream().filter(g -> g.id().equals(id)).map(g -> g.nameKey()).findFirst().orElse("");
        if (!own.isEmpty() && net.minecraft.locale.Language.getInstance().has(own)) return Component.translatable(own);
        String name = Catalog.shortName(id);
        List<String> keys = id.startsWith("formwork:gen/") ? List.of("formwork.gen." + name) : List.of("formwork.structure." + name, "mbtool.structure." + name);
        for (String key : keys)
            if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(Catalog.prettify(name));
    }

}
