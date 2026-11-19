package dev.sylviromi.formwork.ae2;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftConfirmMenu;
import dev.sylviromi.formwork.Formwork;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Crafts every missing block of a structure as one AE2 job. While it runs, a node of ours on the player's network offers
 * one pattern: all the blocks in, a "Structure materials" token out. AE2 plans it in its own confirm screen and runs it on one
 * CPU; when the CPU pushes the pattern we put the blocks back into the network, where the tool takes them, and hand the
 * token back so the job completes. A storage of ours one step below AE2's CPU interceptor swallows the finished token.
 */
final class StructureCraft implements IActionHost {
    private static final Map<UUID, StructureCraft> SESSIONS = new HashMap<>();
    /** How long AE2 gets to pick up the new pattern, and the player to start the job, in ticks. */
    private static final int PATTERN_TIMEOUT = 100;

    private enum Stage {WAITING_FOR_PATTERN, CONFIRMING, CRAFTING, DELIVERED}

    private final UUID playerId;
    private final InteractionHand hand;
    private final AEItemKey token;
    private final Pattern pattern;
    private final IManagedGridNode node;
    private final IActionSource source = IActionSource.ofMachine(this);
    private Stage stage = Stage.WAITING_FOR_PATTERN;
    private int ticks;
    @Nullable
    private KeyCounter pushed;
    private boolean tokenStored;

    /** amounts: what the network has to provide of each block (stock it holds is used first, the rest is crafted). */
    static void start(ServerPlayer player, InteractionHand hand, IGridNode network, Map<Item, Integer> amounts, Component name) {
        StructureCraft running = SESSIONS.get(player.getUUID());
        if (running != null) {
            if (running.stage == Stage.CRAFTING || running.stage == Stage.DELIVERED) {
                tell(player, Component.translatable("formwork.msg.structure_craft.running").withStyle(ChatFormatting.RED));
                return;
            }
            running.end(null);
        }
        if (amounts.isEmpty()) return;
        SESSIONS.put(player.getUUID(), new StructureCraft(player, hand, network, amounts, name));
    }

    private StructureCraft(ServerPlayer player, InteractionHand hand, IGridNode network, Map<Item, Integer> amounts, Component name) {
        this.playerId = player.getUUID();
        this.hand = hand;
        ItemStack stack = new ItemStack(Formwork.STRUCTURE_MATERIALS.get());
        stack.setHoverName(Component.translatable("item.formwork.structure_materials.named", name));
        stack.getOrCreateTag().putUUID("session", UUID.randomUUID()); // one token per job, so concurrent jobs never meet
        this.token = AEItemKey.of(stack);
        List<Input> inputs = new ArrayList<>();
        amounts.forEach((item, n) -> inputs.add(new Input(AEItemKey.of(item), n)));
        this.pattern = new Pattern(token, inputs.toArray(new Input[0]));
        this.node = GridHelper.createManagedNode(this, new IGridNodeListener<StructureCraft>() {
                    @Override
                    public void onSaveChanges(StructureCraft owner, IGridNode n) {
                    }
                })
                .setInWorldNode(false)
                .setVisualRepresentation(Formwork.PLACER.get())
                .addService(ICraftingProvider.class, new Provider())
                .addService(IStorageProvider.class, (IStorageProvider) mounts -> mounts.mount(new TokenSink(), Integer.MAX_VALUE - 1));
        node.setOwningPlayer(player);
        node.create(player.level(), null);
        GridHelper.createConnection(node.getNode(), network);
    }

    @Nullable
    @Override
    public IGridNode getActionableNode() {
        return node.getNode();
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        for (StructureCraft s : List.copyOf(SESSIONS.values())) s.tick(event.getServer().getPlayerList().getPlayer(s.playerId));
    }

    private void tick(@Nullable ServerPlayer player) {
        ticks++;
        IGrid grid = node.getGrid();
        if (grid == null) {
            end(Component.translatable("formwork.msg.structure_craft.cancelled"));
            return;
        }
        if (pushed != null) {
            deliver(grid, player);
            return;
        }
        boolean running = jobRunning(grid);
        switch (stage) {
            case WAITING_FOR_PATTERN -> {
                if (player != null && grid.getCraftingService().isCraftable(token)) {
                    // AE2's plan screen, showing a plan we ask for through our own node (planned through the
                    // screen's host, AE2 did not use our pattern)
                    MenuOpener.open(CraftConfirmMenu.TYPE, player, MenuLocators.forHand(player, hand));
                    if (player.containerMenu instanceof CraftConfirmMenu menu)
                        menu.setJob(grid.getCraftingService().beginCraftingCalculation(player.level(), () -> IActionSource.ofPlayer(player, this),
                                token, 1, CalculationStrategy.REPORT_MISSING_ITEMS));
                    stage = Stage.CONFIRMING;
                } else if (player == null || ticks > PATTERN_TIMEOUT) {
                    end(Component.translatable("formwork.msg.structure_craft.no_pattern"));
                }
            }
            case CONFIRMING -> {
                if (running) stage = Stage.CRAFTING;
                else if (player == null || !(player.containerMenu instanceof CraftConfirmMenu))
                    end(null); // backed out of the plan
            }
            case CRAFTING -> {
                if (!running) end(Component.translatable("formwork.msg.structure_craft.cancelled"));
            }
            case DELIVERED -> {
                if (tokenStored || !running) end(Component.translatable("formwork.msg.structure_craft.done"));
            }
        }
    }

    /** The blocks go back into the network (what does not fit to the player, or their feet), then the token completes the job. */
    private void deliver(IGrid grid, @Nullable ServerPlayer player) {
        MEStorage storage = grid.getStorageService().getInventory();
        for (var e : pushed) {
            long left = e.getLongValue() - storage.insert(e.getKey(), e.getLongValue(), Actionable.MODULATE, source);
            if (left > 0 && player != null && e.getKey() instanceof AEItemKey item)
                for (long n = left; n > 0; ) {
                    int count = (int) Math.min(n, item.getItem().getMaxStackSize());
                    ItemStack stack = item.toStack(count);
                    if (!player.getInventory().add(stack)) player.drop(stack, false);
                    n -= count;
                }
        }
        pushed = null;
        // inserted a tick after the push, when the CPU is waiting for it; AE2's interceptor ranks above our sink
        storage.insert(token, 1, Actionable.MODULATE, source);
        stage = Stage.DELIVERED;
    }

    private boolean jobRunning(IGrid grid) {
        for (var cpu : grid.getCraftingService().getCpus()) {
            var job = cpu.getJobStatus();
            if (job != null && job.crafting().what().equals(token)) return true;
        }
        return false;
    }

    private void end(@Nullable Component message) {
        node.destroy();
        SESSIONS.remove(playerId, this);
        if (message == null) return;
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(playerId);
        if (player != null) tell(player, message);
    }

    private static void tell(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    private final class Provider implements ICraftingProvider {
        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return stage == Stage.DELIVERED ? List.of() : List.of(pattern);
        }

        @Override
        public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
            if (details != pattern || pushed != null || stage == Stage.DELIVERED) return false;
            KeyCounter all = new KeyCounter();
            for (KeyCounter k : inputs) all.addAll(k);
            pushed = all;
            stage = Stage.CRAFTING;
            return true;
        }

        @Override
        public boolean isBusy() {
            return pushed != null || stage == Stage.DELIVERED;
        }
    }

    /** Takes the finished token out of circulation; accepts nothing else. */
    private final class TokenSink implements MEStorage {
        @Override
        public boolean isPreferredStorageFor(AEKey what, IActionSource src) {
            return what.equals(token);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource src) {
            if (!what.equals(token)) return 0;
            if (mode == Actionable.MODULATE) tokenStored = true;
            return amount;
        }

        @Override
        public Component getDescription() {
            return Formwork.STRUCTURE_MATERIALS.get().getDescription();
        }
    }

    private record Pattern(AEItemKey token, Input[] inputs) implements IPatternDetails {
        @Override
        public AEItemKey getDefinition() {
            return token;
        }

        @Override
        public IInput[] getInputs() {
            return inputs;
        }

        @Override
        public GenericStack[] getOutputs() {
            return new GenericStack[]{new GenericStack(token, 1)};
        }
    }

    /** One block: exactly this item, n of them (AE2 keeps the count in the multiplier). */
    private record Input(AEItemKey item, long n) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{new GenericStack(item, 1)};
        }

        @Override
        public long getMultiplier() {
            return n;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return input.equals(item);
        }

        @Nullable
        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
