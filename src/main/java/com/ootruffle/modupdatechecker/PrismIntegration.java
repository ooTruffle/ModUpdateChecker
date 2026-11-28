package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.Prism;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The opt-in "set up the pre-launch check in Prism" (see {@link Prism}). Only does anything when
 * the player asks: then, as the game closes, {@link com.ootruffle.modupdatechecker.install.LauncherSetup}
 * is started to close Prism, add the command and reopen Prism.
 */
public final class PrismIntegration {

    private static volatile Prism.Info info;
    private static volatile boolean requested;
    private static boolean hookAdded;

    private PrismIntegration() {}

    private static Prism.Info info() {
        Prism.Info i = info;
        if (i == null) {
            try {
                i = Prism.detect();
            } catch (Throwable t) {
                i = new Prism.Info(Prism.State.NOT_PRISM, null, null, null, null, null);
            }
            info = i;
        }
        return i;
    }

    public static Prism.State state() {
        if (devShow) return Prism.State.READY;
        return info().state();
    }

    /** Whether the in-game screen should offer it (Prism, not set yet, not dismissed). */
    public static boolean offer() {
        if (devShow) return true;
        return state() == Prism.State.READY && !Config.get().prismPromptDismissed;
    }

    /**
     * Dev autotest only: acts as if started by Prism (banner and OneConfig button shown). Pressing
     * Set up there only sets a flag; the helper can't start without a real Prism.
     */
    static volatile boolean devShow;

    public static boolean requested() {
        return requested;
    }

    /** Opts in: Prism is closed, set up and reopened when the game closes. */
    public static synchronized boolean request() {
        if (state() != Prism.State.READY) return false;
        requested = true;
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(PrismIntegration::startHelper, "ModUpdateChecker-Prism"));
        }
        ModUpdateChecker.LOGGER.info("Prism set-up requested; it runs when the game closes");
        return true;
    }

    public static void cancel() {
        requested = false;
    }

    /** "Not now": stop offering it on the screen (the OneConfig page still has it). */
    public static void dismiss() {
        Config.get().prismPromptDismissed = true;
        Config.save();
    }

    private static void startHelper() {
        if (!requested) return;
        final Prism.Info i = info();
        try {
            final String java = ProcessHandle.current().info().command().orElse(
                    Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java").toString());
            final List<String> parts = new ArrayList<>();
            for (Path p : Env.selfPaths) parts.add(p.toString());
            if (parts.isEmpty()) throw new IllegalStateException("can't find this mod's jar");
            final ProcessBuilder pb = new ProcessBuilder(java, "-cp", String.join(System.getProperty("path.separator"), parts),
                    "com.ootruffle.modupdatechecker.install.LauncherSetup", "prism",
                    Long.toString(ProcessHandle.current().pid()), Long.toString(i.prism().pid()), i.prismExe().toString(),
                    i.instanceCfg().toString(), i.globalCfg() == null ? "-" : i.globalCfg().toString());
            Files.createDirectories(Config.dir());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(Config.dir().resolve("prism-setup.log").toFile()));
            pb.start();
        } catch (Exception e) {
            ModUpdateChecker.LOGGER.warn("Couldn't start the Prism set-up: {}", e.toString());
        }
    }
}
