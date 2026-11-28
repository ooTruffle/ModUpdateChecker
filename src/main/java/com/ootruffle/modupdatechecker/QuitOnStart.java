package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.QuitMarker;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Closes the game straight away when Quit was pressed in the pre-launch window but the launcher
 * started it anyway (OneClient). Runs before Minecraft loads, so the window never opens.
 */
public final class QuitOnStart implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        final Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        final String modsFolder = System.getProperty("fabric.modsFolder");
        final Path modsDir = modsFolder != null && !modsFolder.isBlank() ? gameDir.resolve(modsFolder) : gameDir.resolve("mods");
        if (QuitMarker.take(gameDir, modsDir)) {
            org.apache.logging.log4j.LogManager.getLogger("ModUpdateChecker").info("Quit was chosen in the pre-launch window; closing the game");
            System.exit(0);
        }
    }
}
