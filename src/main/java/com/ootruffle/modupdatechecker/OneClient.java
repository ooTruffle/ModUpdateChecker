package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.install.Installer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Polyfrost's OneClient launcher updates mods itself, so this mod isn't needed there (and two
 * updaters could fight over the same jars). When the game was started by OneClient, the player is
 * told so and offered to remove this mod.
 * <p>
 * Only the launcher counts, not the OneClient modpack (a Prism instance can be named "OneClient"
 * and have its mods): it's OneClient if the launcher brand says so, OneClient's program started
 * the game, or the game folder is in OneClient's data folder ({@code Polyfrost/OneClient}).
 */
public final class OneClient {

    private static volatile Boolean detected;

    private OneClient() {}

    public static boolean detected() {
        Boolean d = detected;
        if (d == null) {
            d = detect();
            detected = d;
            if (d) ModUpdateChecker.LOGGER.info("Started by OneClient, which updates mods itself");
        }
        return d;
    }

    private static boolean detect() {
        try {
            final String brand = System.getProperty("minecraft.launcher.brand", "");
            if (brand.toLowerCase(Locale.ROOT).contains("oneclient")) return true;
            Optional<ProcessHandle> p = ProcessHandle.current().parent();
            for (int depth = 0; depth < 3 && p.isPresent(); depth++) {
                final Optional<String> cmd = p.get().info().command();
                if (cmd.isPresent() && Path.of(cmd.get()).getFileName().toString().toLowerCase(Locale.ROOT).startsWith("oneclient")) {
                    return true;
                }
                p = p.get().parent();
            }
            // OneClient's game folders: <data>/Polyfrost/OneClient/clusters/<name> (or .minecraft).
            final Path dir = Env.gameDir;
            for (int i = 0; i + 1 < dir.getNameCount(); i++) {
                if (dir.getName(i).toString().equalsIgnoreCase("Polyfrost")
                        && dir.getName(i + 1).toString().equalsIgnoreCase("OneClient")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Whether to tell the player (OneClient, and they haven't said "Don't show again"). */
    public static boolean shouldNotify() {
        return (detected() || devShow) && !Config.get().oneClientNoticeDismissed;
    }

    /** Removes this mod's jar when the game closes. Returns false if it can't (dev runs). */
    public static boolean removeThisMod() {
        return Installer.removeSelfOnExit();
    }

    public static boolean removalRequested() {
        return Installer.selfRemovalPending();
    }

    public static void undoRemoval() {
        Installer.cancelSelfRemoval();
    }

    public static void dismiss() {
        Config.get().oneClientNoticeDismissed = true;
        Config.save();
    }

    /** Dev autotest only: acts as if started by OneClient. */
    static volatile boolean devShow;

    static final String TITLE = "OneClient updates your mods";
    static final String MESSAGE = "OneClient already keeps your mods up to date, so Mod Update Checker isn't needed here. "
            + "Remove it to avoid two updaters changing the same mods.";
}
