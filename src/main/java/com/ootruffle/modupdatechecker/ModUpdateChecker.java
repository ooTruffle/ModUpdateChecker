package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.compat.OneConfigCompat;
import com.ootruffle.modupdatechecker.core.ModProblems;
import com.ootruffle.modupdatechecker.install.Installer;
import com.ootruffle.modupdatechecker.install.ModRemover;
import com.ootruffle.modupdatechecker.ui.UpdatesScreen;
import net.minecraft.client.Minecraft;
import net.ornithemc.osl.entrypoints.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Checks every mod in the mods folder for updates: first on Modrinth (matched by the jar's SHA-1,
 * so it works for any mod published there), then on GitHub releases for the rest (the repository
 * comes from the mod's fabric.mod.json, or {@link Config#githubOverrides}).
 * <p>
 * Results show on a PolyCompose screen, opened with the "Mod Updates" button on the title screen
 * and pause menu, or from the mod's page in OneConfig. Downloaded updates are swapped in after the
 * game closes (see {@link Installer}).
 */
public final class ModUpdateChecker implements ModInitializer {

    public static final String MOD_ID = "modupdatechecker";
    public static final Logger LOGGER = LogManager.getLogger("ModUpdateChecker");

    private static volatile boolean openScreen;
    private static boolean problemsShown;

    @Override
    public void init() {
        FabricEnv.install();
        Config.load();
        copyForPrelaunch();
        OneClientIntegration.writeScript();
        Installer.init();
        DevAutotest.early();
        OneConfigCompat.init();
        if (Config.get().checkOnStartup) {
            UpdateChecker.checkAsync(); // Looks for mod problems first.
        } else if (Config.get().checkModProblems) {
            final Thread t = new Thread(() -> ModProblems.log(ModProblems.scan()), "ModUpdateChecker-Problems");
            t.setDaemon(true);
            t.start();
        }
        LOGGER.info("Mod Update Checker initialised");
    }

    /**
     * Copies this mod's jar to {@code config/modupdatechecker/prelaunch.jar}, so a launcher's
     * pre-launch command (see {@link com.ootruffle.modupdatechecker.prelaunch.PrelaunchCheck}) has a
     * path that doesn't change when the mod updates.
     */
    private static void copyForPrelaunch() {
        final java.util.List<java.nio.file.Path> self = com.ootruffle.modupdatechecker.core.Env.selfPaths;
        if (self.size() != 1 || !java.nio.file.Files.isRegularFile(self.get(0))) return; // Dev runs: class folders.
        final java.nio.file.Path target = Config.dir().resolve("prelaunch.jar");
        try {
            final java.nio.file.Path source = self.get(0);
            if (java.nio.file.Files.isRegularFile(target)
                    && java.nio.file.Files.size(target) == java.nio.file.Files.size(source)
                    && UpdateChecker.hash(target, "SHA-1").equals(UpdateChecker.hash(source, "SHA-1"))) {
                return;
            }
            java.nio.file.Files.createDirectories(target.getParent());
            final java.nio.file.Path tmp = target.resolveSibling("prelaunch.jar.tmp");
            java.nio.file.Files.copy(source, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Copied this mod to {} for the pre-launch update check", target);
        } catch (Exception e) {
            LOGGER.warn("Couldn't copy this mod for the pre-launch check: {}", e.toString());
        }
    }

    /**
     * Opens the updates screen on the next tick, on top of whatever is open (so closing it goes
     * back there). Buttons and commands run while their own screen is still in charge, which would
     * replace a screen opened straight away.
     */
    public static void openScreen() {
        openScreen = true;
    }

    /** Called at the end of every client tick. */
    public static void onClientTick() {
        if (openScreen) {
            openScreen = false;
            final Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(UpdatesScreen.create(mc.currentScreen));
        }
        // Duplicate or unusable mods: show them once, on the title screen (what Duplicate Mod Warning did).
        if (!problemsShown && !DevAutotest.active() && Minecraft.getMinecraft().currentScreen instanceof net.minecraft.client.gui.GuiMainMenu
                && ModRemover.problemCount() > 0) {
            problemsShown = true;
            final Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(UpdatesScreen.create(mc.currentScreen));
        }
        OneConfigCompat.onClientTick();
        DevAutotest.tick();
    }
}
