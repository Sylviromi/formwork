package dev.sylviromi.formwork;

import com.mojang.logging.LogUtils;
import dev.sylviromi.formwork.ae2.AeLink;
import dev.sylviromi.formwork.item.PlacerItem;
import dev.sylviromi.formwork.net.Net;
import dev.sylviromi.formwork.structure.CatalogLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import dev.sylviromi.formwork.build.PlaceCommand;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(Formwork.ID)
public class Formwork {
    public static final String ID = "formwork";
    public static final Logger LOG = LogUtils.getLogger();
    public static final boolean AE2 = ModList.get().isLoaded("ae2");

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    // with AE2 the tool can host AE2's craft screens; AeLink is only touched when AE2 is loaded
    public static final RegistryObject<Item> PLACER = ITEMS.register("placer", () -> AE2 ? AeLink.placerItem() : new PlacerItem());
    /** The token an AE2 structure-materials job makes (see ae2/StructureCraft); never a real item in an inventory. */
    public static final RegistryObject<Item> STRUCTURE_MATERIALS = AE2 ? ITEMS.register("structure_materials", () -> new Item(new Item.Properties().stacksTo(1))) : null;

    public Formwork() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::setup);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(PLACER);
        });
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new CatalogLoader()));
        MinecraftForge.EVENT_BUS.addListener(Net::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) Net.syncPlacements(p); });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) Net.syncPlacements(p); });
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> PlaceCommand.register(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener(dev.sylviromi.formwork.build.Updater::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(dev.sylviromi.formwork.build.Animator::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(dev.sylviromi.formwork.build.Animator::finishAll);
        if (AE2) MinecraftForge.EVENT_BUS.addListener(AeLink::onServerTick);
    }

    private void setup(FMLCommonSetupEvent event) {
        Net.register();
        if (AE2) event.enqueueWork(() -> AeLink.register(PLACER.get()));
    }
}
