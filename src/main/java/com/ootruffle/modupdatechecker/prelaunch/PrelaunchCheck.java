package com.ootruffle.modupdatechecker.prelaunch;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.UpdateChecker;
import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.JarScanner;
import com.ootruffle.modupdatechecker.core.ModProblems;
import com.ootruffle.modupdatechecker.install.Installer;
import com.ootruffle.modupdatechecker.install.ModRemover;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks for (and optionally installs) mod updates before the game starts, as a launcher's
 * pre-launch command. In Prism Launcher (Edit instance → Settings → Custom commands):
 * <pre>
 * "$INST_JAVA" -cp "$INST_MC_DIR/config/modupdatechecker/prelaunch.jar" com.ootruffle.modupdatechecker.prelaunch.PrelaunchCheck --install
 * </pre>
 * The mod copies itself to {@code prelaunch.jar} each time the game starts, so the command keeps
 * working when the mod updates (the game has to have been started once with the mod).
 * <p>
 * The {@code prelaunchMode} setting in {@code config/modupdatechecker.json} decides what it does
 * ({@code window}, {@code install}, {@code log} or {@code off}). With the default, {@code window},
 * a mode option in the command ({@code --install}, or {@code --gui}) is used instead, so commands
 * set up by hand keep working; any other setting wins over them. {@code --launcher oneclient} says OneClient started it: OneClient launches
 * the game even when this fails, so the window has no Quit button there.
 * <p>
 * Options: {@code --gui} opens a window listing the updates, and problems in the mods folder, to pick from (see {@link PrelaunchGui});
 * it closes by itself when there's nothing to update. {@code --install} swaps updates in straight away, after moving extra copies of a mod out (nothing holds the jars yet; without
 * it, updates are only listed in the launcher's log), {@code --game-dir <dir>} (default: Prism's
 * {@code INST_MC_DIR}, or the folder this jar is in), {@code --timeout <seconds>} (default 60),
 * {@code --verbose} lists every mod.
 * <p>
 * It exits with 0, so a failed check (offline, say) never stops the game from launching. The one
 * exception is the window's Quit button, which exits with 1 so the launcher cancels the launch.
 */
public final class PrelaunchCheck {

    private PrelaunchCheck() {}

    public static void main(String[] args) {
        boolean launch = true;
        try {
            launch = run(args);
        } catch (Throwable t) {
            System.out.println("[ModUpdateChecker] Pre-launch check failed: " + t);
        }
        if (!launch) System.out.println("[ModUpdateChecker] Quit chosen: not launching the game.");
        System.out.flush();
        // Only Quit stops the launch (launchers cancel it when a pre-launch command fails).
        System.exit(launch ? 0 : 1);
    }

    /** Returns false only when the player pressed Quit in the --gui window. */
    private static boolean run(String[] args) throws Exception {
        boolean install = false;
        boolean gui = false;
        boolean verbose = false;
        boolean canCancel = true;
        int timeout = 60;
        Path gameDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install" -> install = true;
                case "--gui" -> gui = true;
                case "--verbose" -> verbose = true;
                case "--game-dir" -> gameDir = Paths.get(args[++i]);
                case "--timeout" -> timeout = Integer.parseInt(args[++i]);
                case "--mc" -> Env.minecraftVersion = args[++i];
                case "--launcher" -> canCancel = !"oneclient".equalsIgnoreCase(args[++i]);
                default -> System.out.println("[ModUpdateChecker] Ignoring unknown option " + args[i]);
            }
        }
        if (gameDir == null) gameDir = findGameDir();
        Env.gameDir = gameDir.toAbsolutePath().normalize();
        Env.scanner = JarScanner::scan;
        Env.verbose = verbose;
        Config.load();
        final String mode = Config.get().prelaunchMode;
        if (Config.PRELAUNCH_OFF.equals(mode)) {
            System.out.println("[ModUpdateChecker] Pre-launch check is off (prelaunchMode in config/modupdatechecker.json).");
            return true;
        }
        if (!Config.PRELAUNCH_WINDOW.equals(mode)) {
            gui = false;
            install = Config.PRELAUNCH_INSTALL.equals(mode);
        } else if (!install) {
            gui = true;
        }
        System.out.println("[ModUpdateChecker] Checking " + Env.modsDir() + (install ? " (installing updates)" : ""));

        if (gui) {
            if (PrelaunchGui.available()) {
                // The window waits for the player, so the timeout only covers the check itself.
                final boolean launch = PrelaunchGui.run(timeout, canCancel);
                System.out.println("[ModUpdateChecker] " + UpdateChecker.summary().replace(" · ", ", "));
                return launch;
            }
            System.out.println("[ModUpdateChecker] No display for --gui; just checking.");
        }

        final boolean doInstall = install;
        final List<String> report = new ArrayList<>();
        final Thread worker = new Thread(() -> {
            if (doInstall && Config.get().checkModProblems) {
                // Extra copies of a mod (same file or mod id) and mods this one replaces go first,
                // so the update check sees the copy that's kept. Name-only matches and mods for
                // other loaders are only listed: they're worth a look first.
                final ModProblems.Result problems = ModProblems.scan();
                final List<ModProblems.Jar> remove = new ArrayList<>(ModRemover.extraCopies(problems, false));
                remove.addAll(problems.replaced);
                for (ModProblems.Jar j : remove) {
                    report.add("  " + j.fileName() + (ModRemover.remove(j) ? ": moved to removed-mods" : ": couldn't be moved"));
                }
            }
            UpdateChecker.checkNow();
            for (ModStatus m : UpdateChecker.mods()) {
                if (!m.wantsUpdate()) continue;
                final String line = "  " + m.name + " " + m.version + " -> " + m.latestVersion
                        + " (" + (m.source == ModStatus.Source.MODRINTH ? "Modrinth" : "GitHub") + ")";
                if (!m.canDownload()) {
                    report.add(line + ": no file to download, get it from " + m.pageUrl);
                } else if (!doInstall) {
                    report.add(line);
                } else if (Installer.installNow(m)) {
                    report.add(line + ": installed");
                } else {
                    report.add(line + ": " + m.error);
                }
            }
        }, "ModUpdateChecker-Prelaunch");
        worker.setDaemon(true);
        worker.start();
        worker.join(timeout * 1000L);

        if (worker.isAlive()) {
            System.out.println("[ModUpdateChecker] Gave up after " + timeout + "s; launching anyway.");
            return true;
        }
        // Plain ASCII: launchers don't always read a command's output as UTF-8.
        System.out.println("[ModUpdateChecker] " + UpdateChecker.summary().replace(" · ", ", "));
        for (String line : report) System.out.println("[ModUpdateChecker] " + line);
        if ((!report.isEmpty() || !ModProblems.current().isEmpty()) && !doInstall) {
            System.out.println("[ModUpdateChecker] Set prelaunchMode to \"window\" or \"install\" in config/modupdatechecker.json"
                    + " to fix this before launching, or use the Mod Updates screen in game.");
        }
        return true;
    }

    /** Prism's INST_MC_DIR, else three folders up from this jar (it lives in <game>/config/modupdatechecker). */
    private static Path findGameDir() {
        final String inst = System.getenv("INST_MC_DIR");
        if (inst != null && !inst.isEmpty()) return Paths.get(inst);
        try {
            final Path jar = Paths.get(PrelaunchCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            final Path up = jar.getParent() == null ? null : jar.getParent().getParent();
            if (up != null && up.getParent() != null && up.getFileName().toString().equals("config")
                    && Files.isDirectory(up.getParent().resolve("mods"))) {
                return up.getParent();
            }
        } catch (Exception ignored) {
        }
        return Paths.get(".");
    }
}
