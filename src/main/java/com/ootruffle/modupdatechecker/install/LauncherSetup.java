package com.ootruffle.modupdatechecker.install;

import com.ootruffle.modupdatechecker.core.OneClientLauncher;
import com.ootruffle.modupdatechecker.core.Prism;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finishes setting up the pre-launch check in a launcher after the game closes, in its own small
 * Java process (JDK only): waits for the game to exit, closes the launcher, adds the command to
 * its settings, and opens the launcher again.
 * <p>
 * Prism and OneClient both keep their settings in memory and save them from there, so the
 * launcher must be closed while the file is edited. It's asked to close first; if it hasn't after
 * a few seconds, it's force-closed (taskkill /F) and reopened. The exception is when it's still
 * running another game: then this waits until the launcher is closed by hand, edits the file, and
 * doesn't reopen it. With no launcher running (pid 0), the file is edited straight away.
 * <p>
 * Arguments: {@code prism <game pid> <prism pid> <prism exe> <instance.cfg> <prismlauncher.cfg|->}
 * or {@code oneclient <game pid> <oneclient pid|0> <oneclient exe|-> <settings.json>}.
 */
public final class LauncherSetup {

    /** How long the launcher gets to close by itself before it's force-closed. */
    private static final long CLOSE_WAIT_SECONDS = 5;
    private static final long FORCE_WAIT_SECONDS = 10;

    private LauncherSetup() {}

    /** Edits the launcher's settings once it's closed; returns what happened. */
    private interface Patch {
        String apply() throws Exception;
    }

    public static void main(String[] args) {
        try {
            if (args.length == 6 && args[0].equals("prism")) {
                final Path instanceCfg = Paths.get(args[4]);
                final Path globalCfg = args[5].equals("-") ? null : Paths.get(args[5]);
                run("Prism", Long.parseLong(args[1]), Long.parseLong(args[2]), Paths.get(args[3]),
                        () -> "instance.cfg: " + Prism.patch(instanceCfg, globalCfg));
            } else if (args.length == 5 && args[0].equals("oneclient")) {
                final Path settings = Paths.get(args[4]);
                run("OneClient", Long.parseLong(args[1]), Long.parseLong(args[2]), args[3].equals("-") ? null : Paths.get(args[3]),
                        () -> "settings.json: " + OneClientLauncher.patch(settings));
            } else {
                log("usage: LauncherSetup prism <game pid> <prism pid> <prism exe> <instance.cfg> <prismlauncher.cfg|->");
                log("       LauncherSetup oneclient <game pid> <oneclient pid|0> <oneclient exe|-> <settings.json>");
                System.exit(2);
            }
        } catch (Throwable t) {
            log("set-up failed: " + t);
        }
    }

    private static void run(String name, long gamePid, long launcherPid, Path launcherExe, Patch patch) throws Exception {
        log("waiting for the game (pid " + gamePid + ") to close");
        ProcessHandle.of(gamePid).ifPresent(game -> {
            try {
                game.onExit().get(10, TimeUnit.MINUTES);
            } catch (Exception e) {
                log("  gave up waiting for the game: " + e);
            }
        });
        // Let the launcher save the play time (Prism rewrites instance.cfg right after the game exits).
        Thread.sleep(2500);

        final Optional<ProcessHandle> launcher = launcherPid > 0 ? ProcessHandle.of(launcherPid) : Optional.empty();
        boolean reopen = false;
        if (launcher.isPresent() && launcher.get().isAlive()) {
            final ProcessHandle p = launcher.get();
            log("closing " + name + " (pid " + launcherPid + ")");
            requestClose(p);
            if (waitFor(p, CLOSE_WAIT_SECONDS)) {
                reopen = true;
            } else if (runningGames(p) > 0) {
                // Killing the launcher now would make it lose track of that game (and its play time).
                log(name + " is still running another game, so it isn't force-closed;"
                        + " the setting is added when you close " + name + " yourself");
                p.onExit().get(24, TimeUnit.HOURS);
            } else {
                log(name + " didn't close by itself; force-closing it");
                forceClose(p);
                if (waitFor(p, FORCE_WAIT_SECONDS)) {
                    reopen = true;
                } else {
                    log(name + " still didn't close; the setting is added when it does");
                    p.onExit().get(24, TimeUnit.HOURS);
                }
            }
            Thread.sleep(500);
        }
        log(patch.apply());
        if (reopen && launcherExe != null) {
            log("opening " + name + " again");
            final ProcessBuilder pb = new ProcessBuilder(launcherExe.toString());
            final File dir = launcherExe.getParent() == null ? null : launcherExe.getParent().toFile();
            if (dir != null) pb.directory(dir);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        }
        log("done");
    }

    /** Asks the program to close, as its close button would (never a forced kill). */
    private static void requestClose(ProcessHandle p) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            // Without /F, taskkill sends the program's windows a normal close message.
            new ProcessBuilder("taskkill", "/PID", Long.toString(p.pid()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(10, TimeUnit.SECONDS);
        } else {
            p.destroy(); // SIGTERM: launchers close normally.
        }
    }

    /** Force-closes the program (taskkill /F on Windows, SIGKILL elsewhere). */
    private static void forceClose(ProcessHandle p) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            new ProcessBuilder("taskkill", "/F", "/PID", Long.toString(p.pid()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(10, TimeUnit.SECONDS);
        }
        if (p.isAlive()) p.destroyForcibly();
    }

    /** Games (Java processes) the launcher is still running, besides the one that just closed. */
    private static long runningGames(ProcessHandle launcher) {
        return launcher.children().filter(c -> c.isAlive() && c.info().command()
                .map(cmd -> {
                    final String name = Path.of(cmd).getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.startsWith("java");
                }).orElse(false)).count();
    }

    private static boolean waitFor(ProcessHandle p, long seconds) {
        try {
            p.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return !p.isAlive();
        }
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now() + " " + message);
    }
}
