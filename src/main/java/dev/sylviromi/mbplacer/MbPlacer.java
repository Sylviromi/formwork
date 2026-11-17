package dev.sylviromi.mbplacer;

import com.mojang.logging.LogUtils;
import dev.sylviromi.mbplacer.ae2.AeLink;
import dev.sylviromi.mbplacer.item.PlacerItem;
import dev.sylviromi.mbplacer.net.Net;
import dev.sylviromi.mbplacer.structure.CatalogLoader;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(MbPlacer.ID)
public class MbPlacer {
    public static final String ID = "mbplacer";
    public static final Logger LOG = LogUtils.getLogger();
    public static final boolean AE2 = ModList.get().isLoaded("ae2");

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final RegistryObject<Item> PLACER = ITEMS.register("placer", PlacerItem::new);

    public MbPlacer() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::setup);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(PLACER);
        });
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new CatalogLoader()));
        MinecraftForge.EVENT_BUS.addListener(Net::onDatapackSync);
        if (AE2) MinecraftForge.EVENT_BUS.addListener(AeLink::onServerTick);
    }

    private void setup(FMLCommonSetupEvent event) {
        Net.register();
        if (AE2) event.enqueueWork(() -> AeLink.register(PLACER.get()));
    }
}
