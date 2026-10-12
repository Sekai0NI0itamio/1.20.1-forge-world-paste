package com.worldpaste;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * WorldPaste — copy a zipped world into the live server.
 * Server-side only logic; safe to install on server (and client for single-player).
 */
@Mod(WorldPasteMod.MOD_ID)
public class WorldPasteMod {
    public static final String MOD_ID = "worldpaste";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldPasteMod() {
        MinecraftForge.EVENT_BUS.register(CopyCommand.class);
        MinecraftForge.EVENT_BUS.register(PasteSession.class);
        LOGGER.info("WorldPaste loaded. Put source zips in ./worldpaste_inbox/ and run /copy world <zip> <overworld|the_nether|the_end> <x> <z>");
    }
}
