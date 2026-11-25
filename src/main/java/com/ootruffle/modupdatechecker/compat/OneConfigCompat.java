package com.ootruffle.modupdatechecker.compat;

import com.ootruffle.modupdatechecker.ModUpdateChecker;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional OneConfig support: with OneConfig 1.9+, {@link ModUpdateCheckerPage} adds a page to its
 * menu and {@code /modupdates}. This class never touches OneConfig's classes itself, so it's safe
 * to load without OneConfig.
 */
public final class OneConfigCompat {

    // "oneconfig" from 1.9 on (the versions that run on PolyCompose); "oneconfigv1" before that.
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("oneconfig")
            || FabricLoader.getInstance().isModLoaded("oneconfigv1");
    private static final int SYNC_INTERVAL_TICKS = 20;

    private static boolean active;
    private static int ticks;

    private OneConfigCompat() {}

    public static void init() {
        if (!LOADED) return;
        try {
            ModUpdateCheckerPage.init();
            active = true;
            ModUpdateChecker.LOGGER.info("OneConfig found; Mod Update Checker has a page in its menu and /modupdates");
        } catch (Throwable t) {
            ModUpdateChecker.LOGGER.warn("OneConfig found, but its page couldn't be set up (this needs OneConfig 1.9 or newer): {}", t.toString());
        }
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * The "OneClient updates your mods" notification, with Remove and Don't show again. Returns
     * false without OneConfig (or if it couldn't be shown).
     */
    public static boolean notifyOneClient(String title, String message) {
        if (!active) return false;
        try {
            ModUpdateCheckerPage.notifyOneClient(title, message);
            return true;
        } catch (Throwable t) {
            ModUpdateChecker.LOGGER.warn("Couldn't show a OneConfig notification: {}", t.toString());
            return false;
        }
    }

    // Only static calls into ModUpdateCheckerPage here: anything that hands its INSTANCE to a
    // OneConfig method makes the JVM load OneConfig's classes when this class loads, which crashes
    // the game without OneConfig.

    /** Dev autotest: opens this mod's OneConfig page. */
    public static void devOpenPage() {
        ModUpdateCheckerPage.devOpen();
    }

    /** Dev autotest: presses one of the page's buttons. */
    public static void devPress(String button) {
        ModUpdateCheckerPage.devPress(button);
    }

    /** Called every client tick: copies the page's settings across once a second. */
    public static void onClientTick() {
        if (active && ++ticks >= SYNC_INTERVAL_TICKS) {
            ticks = 0;
            try {
                ModUpdateCheckerPage.sync();
            } catch (Throwable t) {
                active = false;
                ModUpdateChecker.LOGGER.warn("Stopped syncing the OneConfig page: {}", t.toString());
            }
        }
    }
}
