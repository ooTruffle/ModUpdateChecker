package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.OneClientLauncher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The opt-in "set up the pre-launch check in OneClient" (see {@link OneClientLauncher}). The
 * script the command runs is written every time the game starts in a OneClient cluster; the
 * command itself is only added when the player asks: then, as the game closes,
 * {@link com.ootruffle.modupdatechecker.install.LauncherSetup} closes OneClient, adds it and
 * reopens OneClient.
 */
public final class OneClientIntegration {

    private static volatile OneClientLauncher.Info info;
    private static volatile boolean requested;
    private static boolean hookAdded;

    private OneClientIntegration() {}

    private static OneClientLauncher.Info info() {
        OneClientLauncher.Info i = info;
        if (i == null) {
            try {
                i = OneClientLauncher.detect();
            } catch (Throwable t) {
                i = new OneClientLauncher.Info(OneClientLauncher.State.NOT_ONECLIENT, null, null, null);
            }
            info = i;
        }
        return i;
    }

    public static OneClientLauncher.State state() {
        if (devShow) return OneClientLauncher.State.READY;
        return info().state();
    }

    /** Whether the in-game screen should offer it (OneClient, not set yet, not dismissed). */
    public static boolean offer() {
        if (devShow) return true;
        return state() == OneClientLauncher.State.READY && !Config.get().oneClientPromptDismissed;
    }

    /** Dev autotest only: acts as if started by OneClient (Set up then only sets a flag). */
    static volatile boolean devShow;

    public static boolean requested() {
        return requested;
    }

    /** Writes the script the pre-launch command runs (called as the game starts). */
    static void writeScript() {
        if (state() == OneClientLauncher.State.NOT_ONECLIENT || devShow) return;
        try {
            OneClientLauncher.writeScript(Config.dir(), java());
        } catch (Exception e) {
            ModUpdateChecker.LOGGER.warn("Couldn't write the OneClient pre-launch script: {}", e.toString());
        }
    }

    /** Opts in: OneClient is closed, set up and reopened when the game closes. */
    public static synchronized boolean request() {
        if (state() != OneClientLauncher.State.READY) return false;
        requested = true;
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(OneClientIntegration::startHelper, "ModUpdateChecker-OneClient"));
        }
        ModUpdateChecker.LOGGER.info("OneClient set-up requested; it runs when the game closes");
        return true;
    }

    public static void cancel() {
        requested = false;
    }

    /** "Not now": stop offering it on the screen (the OneConfig page still has it). */
    public static void dismiss() {
        Config.get().oneClientPromptDismissed = true;
        Config.save();
    }

    /** The Java the game runs on (javaw on Windows, so no console window opens). */
    private static Path java() {
        final Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            final Path w = bin.resolve("javaw.exe");
            return Files.isRegularFile(w) ? w : ProcessHandle.current().info().command().map(Path::of).orElse(bin.resolve("java.exe"));
        }
        return bin.resolve("java");
    }

    private static void startHelper() {
        if (!requested) return;
        final OneClientLauncher.Info i = info();
        try {
            final List<String> parts = new ArrayList<>();
            for (Path p : Env.selfPaths) parts.add(p.toString());
            if (parts.isEmpty()) throw new IllegalStateException("can't find this mod's jar");
            final ProcessBuilder pb = new ProcessBuilder(java().toString(), "-cp", String.join(System.getProperty("path.separator"), parts),
                    "com.ootruffle.modupdatechecker.install.LauncherSetup", "oneclient",
                    Long.toString(ProcessHandle.current().pid()),
                    i.oneClient() == null ? "0" : Long.toString(i.oneClient().pid()),
                    i.oneClientExe() == null ? "-" : i.oneClientExe().toString(),
                    i.settingsJson().toString());
            Files.createDirectories(Config.dir());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Config.dir().resolve("oneclient-setup.log").toFile()));
            pb.start();
        } catch (Exception e) {
            ModUpdateChecker.LOGGER.warn("Couldn't start the OneClient set-up: {}", e.toString());
        }
    }
}
