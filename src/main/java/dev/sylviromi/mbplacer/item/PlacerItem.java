package dev.sylviromi.mbplacer.item;

import dev.sylviromi.mbplacer.build.Placer;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) openScreen();
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (ctx.getLevel().isClientSide) openScreen();
            return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
        }
        if (player instanceof ServerPlayer sp) {
            BlockHitResult hit = new BlockHitResult(ctx.getClickLocation(), ctx.getClickedFace(), ctx.getClickedPos(), ctx.isInside());
            Placer.place(sp, ctx.getItemInHand(), target(ctx.getLevel(), hit), ctx.getHorizontalDirection());
        }
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
