package com.ootruffle.modupdatechecker.compat;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.ModUpdateChecker;
import com.ootruffle.modupdatechecker.UpdateChecker;
import com.ootruffle.modupdatechecker.install.Installer;
import org.polyfrost.oneconfig.api.commands.v1.CommandManager;
import org.polyfrost.oneconfig.api.config.v1.annotations.Button;
import org.polyfrost.oneconfig.api.config.v1.annotations.Slider;
import org.polyfrost.oneconfig.api.config.v1.annotations.Switch;
import org.polyfrost.oneconfig.api.notifications.v1.Notifications;
import org.polyfrost.oneconfig.utils.v1.dsl.ScreensKt;

/**
 * The OneConfig page (Utility → Mod Update Checker, or {@code /modupdates}). Only loaded through
 * {@link OneConfigCompat} when OneConfig is installed. The settings mirror {@link Config};
 * {@link #sync()} copies changes across, so the mod reads its settings from one place either way.
 */
public final class ModUpdateCheckerPage extends org.polyfrost.oneconfig.api.config.v1.Config {

    static final ModUpdateCheckerPage INSTANCE = new ModUpdateCheckerPage();

    @Button(title = "Mod Updates", description = "See which of your mods have updates, with their changelogs.", text = "Open", subcategory = "Updates")
    public void openScreen() {
        ModUpdateChecker.openScreen(); // Next tick, on top of this page.
    }

    @Button(title = "Update All", description = "Download every available update (except the ones you ignored). They're installed when you close the game.",
            text = "Update all", subcategory = "Updates")
    public void updateAll() {
        if (UpdateChecker.running()) {
            Notifications.warning("Mod Update Checker", "Still checking for updates; try again in a moment.");
            return;
        }
        int count = 0;
        int manual = 0;
        for (ModStatus m : UpdateChecker.mods()) {
            if (!m.wantsUpdate()) continue;
            if (m.canDownload()) count++;
            else manual++;
        }
        if (count == 0) {
            Notifications.info("Mod Update Checker", manual == 0 ? "Everything is up to date."
                    : manual + (manual == 1 ? " update has" : " updates have") + " no file to download; open Mod Updates to get "
                    + (manual == 1 ? "it" : "them") + " from the mod's page.");
            return;
        }
        Installer.downloadAll();
        Notifications.success("Mod Update Checker", "Downloading " + count + (count == 1 ? " update" : " updates")
                + ". They're installed when you close the game." + (manual > 0 ? " " + manual + " more need downloading by hand." : ""));
    }

    @Button(title = "Check Again", description = "Look for updates now (GitHub answers are asked for again too).", text = "Check", subcategory = "Updates")
    public void checkAgain() {
        UpdateChecker.clearCache();
        UpdateChecker.checkAsync();
        Notifications.info("Mod Update Checker", "Checking your mods for updates...");
    }

    @Button(title = "Pre-launch Check in Prism", description = "Show the update window before the game starts. Prism closes and reopens once, when you close the game, to add it.",
            text = "Set up", subcategory = "Updates")
    public void setUpPrism() {
        final String title = "Mod Update Checker";
        switch (com.ootruffle.modupdatechecker.PrismIntegration.state()) {
            case CONFIGURED -> Notifications.info(title, "The pre-launch check is already set up for this instance.");
            case OTHER_COMMAND -> Notifications.warning(title, "This instance already has a different pre-launch command, so it wasn't changed.");
            case NOT_PRISM -> Notifications.warning(title, "The game wasn't started by Prism Launcher.");
            case READY -> {
                com.ootruffle.modupdatechecker.PrismIntegration.request();
                Notifications.success(title, "Prism will close and reopen when you close the game, to add the pre-launch check.");
            }
        }
    }

    @Switch(title = "Check on Startup", description = "Look for updates when the game starts.", subcategory = "Settings")
    public boolean checkOnStartup = true;

    @Switch(title = "Menu Button", description = "Show the Mod Updates button on the title screen and pause menu.", subcategory = "Settings")
    public boolean showMenuButton = true;

    @Switch(title = "Find Mod Problems", description = "Look for duplicate mods and mods for other loaders, and offer to remove them.", subcategory = "Settings")
    public boolean checkModProblems = true;

    @Slider(title = "GitHub Cache (minutes)", description = "How long a GitHub answer is reused before asking again (GitHub allows 60 requests an hour without a token).",
            min = 0, max = 1440, step = 30, subcategory = "Settings")
    public int githubCacheMinutes = 360;

    private ModUpdateCheckerPage() {
        super("modupdatechecker-oneconfig.json", "Mod Update Checker", Category.UTILITY);
        // Only offered when the game was started by Prism.
        hideIf("setUpPrism", () -> com.ootruffle.modupdatechecker.PrismIntegration.state()
                == com.ootruffle.modupdatechecker.core.Prism.State.NOT_PRISM);
    }

    static void init() {
        // Start from modupdatechecker.json, so settings made without OneConfig carry over.
        final Config c = Config.get();
        INSTANCE.checkOnStartup = c.checkOnStartup;
        INSTANCE.showMenuButton = c.showMenuButton;
        INSTANCE.checkModProblems = c.checkModProblems;
        INSTANCE.githubCacheMinutes = c.githubCacheMinutes;
        INSTANCE.preload();
        CommandManager.INSTANCE.register(ScreensKt.addDefaultCommand(INSTANCE, "modupdates"));
        sync();
    }

    static void notifyOneClient(String title, String message) {
        Notifications.builder(title, message)
                .type(org.polyfrost.oneconfig.api.notifications.v1.NotificationType.WARNING)
                .persistent()
                .action(new org.polyfrost.oneconfig.api.notifications.v1.NotificationAction("Remove", true, () -> {
                    if (com.ootruffle.modupdatechecker.OneClient.removeThisMod()) {
                        Notifications.success("Mod Update Checker", "It'll be removed when you close the game.");
                    } else {
                        Notifications.warning("Mod Update Checker", "Couldn't remove it automatically; delete its jar from the mods folder.");
                    }
                }))
                .action(new org.polyfrost.oneconfig.api.notifications.v1.NotificationAction("Don't show again", false,
                        com.ootruffle.modupdatechecker.OneClient::dismiss))
                .send();
    }

    static void devOpen() {
        ScreensKt.openUI(INSTANCE);
    }

    static void devPress(String button) {
        switch (button) {
            case "updateAll" -> INSTANCE.updateAll();
            case "openScreen" -> INSTANCE.openScreen();
            default -> throw new IllegalArgumentException(button);
        }
    }

    /** Copies the page's settings into {@link Config}, saving it if anything changed. */
    static void sync() {
        final Config c = Config.get();
        if (c.checkOnStartup == INSTANCE.checkOnStartup && c.showMenuButton == INSTANCE.showMenuButton && c.checkModProblems == INSTANCE.checkModProblems
                && c.githubCacheMinutes == INSTANCE.githubCacheMinutes) {
            return;
        }
        c.checkOnStartup = INSTANCE.checkOnStartup;
        c.showMenuButton = INSTANCE.showMenuButton;
        c.checkModProblems = INSTANCE.checkModProblems;
        c.githubCacheMinutes = INSTANCE.githubCacheMinutes;
        Config.save();
    }
}
