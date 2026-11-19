package dev.sylviromi.mbplacer.build;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.sylviromi.mbplacer.structure.BlockSpec;
import dev.sylviromi.mbplacer.structure.Catalog;
import dev.sylviromi.mbplacer.structure.Selection;
import dev.sylviromi.mbplacer.structure.VariantSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * /mbplacer place <entry> <pos> [args]: places a multiblock for free (ops only). For pack makers and testing.
 * args: numbers are generator params in order; "block=count" tokens set variant counts, e.g. voidminers:verdium_speed_modifier=4.
 */
public final class PlaceCommand {
    private PlaceCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mbplacer").requires(s -> s.hasPermission(2))
                .then(Commands.literal("place").then(Commands.argument("entry", ResourceLocationArgument.id())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> place(c, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(c -> place(c, StringArgumentType.getString(c, "args"))))))));
    }

    private static int place(CommandContext<CommandSourceStack> c, String args) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String entry = ResourceLocationArgument.getId(c, "entry").toString();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        List<Integer> params = new ArrayList<>();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (String tok : args.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int eq = tok.indexOf('=');
            if (eq < 0) {
                params.add(Integer.parseInt(tok));
                continue;
            }
            String block = tok.substring(0, eq);
            Map<BlockPos, BlockSpec> plain = Resolver.specs(Catalog.server, new Selection(entry, params.stream().mapToInt(Integer::intValue).toArray(), Map.of()));
            VariantSet set = plain == null ? null : Resolver.slots(Catalog.server, entry, plain).values().stream()
                    .filter(s -> s.blocks().contains(block)).findFirst().orElse(null);
            if (set == null) {
                c.getSource().sendFailure(Component.literal("Block is in no variant set: " + block));
                return 0;
            }
            counts.computeIfAbsent(set.id(), k -> new LinkedHashMap<>()).put(block, Integer.parseInt(tok.substring(eq + 1)));
        }
        Selection sel = new Selection(entry, params.stream().mapToInt(Integer::intValue).toArray(), counts);
        Map<BlockPos, BlockSpec> specs = Resolver.specs(Catalog.server, sel);
        if (specs == null) {
            c.getSource().sendFailure(Component.literal("Unknown entry or invalid variants: " + entry));
            return 0;
        }
        var blocks = Resolver.world(specs, pos, Direction.NORTH);
        var level = c.getSource().getLevel();
        Placer.put(level, blocks, null);
        Placements.of(level).add(level, Placements.record(blocks, sel.save(), pos, Direction.NORTH, null));
        c.getSource().sendSuccess(() -> Component.literal("Placed " + blocks.size() + " blocks of " + entry), true);
        return blocks.size();
    }
}
