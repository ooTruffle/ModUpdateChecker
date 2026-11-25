package com.ootruffle.modupdatechecker.install;

import com.ootruffle.modupdatechecker.core.Prism;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finishes the Prism set-up after the game closes, in its own small Java process (JDK only):
 * waits for the game to exit, closes Prism, adds the pre-launch check to instance.cfg, and opens
 * Prism again.
 * <p>
 * Prism rewrites instance.cfg from memory, so it must be closed while the file is edited. It's
 * asked to close first; if it hasn't after a few seconds, it's force-closed (taskkill /F) and
 * reopened. The exception is when Prism is still running another game: then this waits until
 * Prism is closed by hand, edits the file, and doesn't reopen it.
 * <p>
 * Arguments: game pid, Prism pid, Prism's program, instance.cfg, prismlauncher.cfg (or "-").
 */
public final class PrismSetup {

    /** How long Prism gets to close by itself before it's force-closed. */
    private static final long CLOSE_WAIT_SECONDS = 5;
    private static final long FORCE_WAIT_SECONDS = 10;

    private PrismSetup() {}

    public static void main(String[] args) {
        if (args.length != 5) {
            log("usage: PrismSetup <game pid> <prism pid> <prism exe> <instance.cfg> <prismlauncher.cfg|->");
            System.exit(2);
        }
        try {
            run(Long.parseLong(args[0]), Long.parseLong(args[1]), Paths.get(args[2]), Paths.get(args[3]),
                    args[4].equals("-") ? null : Paths.get(args[4]));
        } catch (Throwable t) {
            log("set-up failed: " + t);
        }
    }

    private static void run(long gamePid, long prismPid, Path prismExe, Path instanceCfg, Path globalCfg) throws Exception {
        log("waiting for the game (pid " + gamePid + ") to close");
        ProcessHandle.of(gamePid).ifPresent(game -> {
            try {
                game.onExit().get(10, TimeUnit.MINUTES);
            } catch (Exception e) {
                log("  gave up waiting for the game: " + e);
            }
        });
        // Let Prism save the play time (it rewrites instance.cfg right after the game exits).
        Thread.sleep(2500);

        final Optional<ProcessHandle> prism = ProcessHandle.of(prismPid);
        boolean reopen = false;
        if (prism.isPresent() && prism.get().isAlive()) {
            final ProcessHandle p = prism.get();
            log("closing Prism (pid " + prismPid + ")");
            requestClose(p);
            if (waitFor(p, CLOSE_WAIT_SECONDS)) {
                reopen = true;
            } else if (runningGames(p) > 0) {
                // Killing Prism now would make it lose track of that game (and its play time).
                log("Prism is still running another game, so it isn't force-closed;"
                        + " the setting is added when you close Prism yourself");
                p.onExit().get(24, TimeUnit.HOURS);
            } else {
                log("Prism didn't close by itself; force-closing it");
                forceClose(p);
                if (waitFor(p, FORCE_WAIT_SECONDS)) {
                    reopen = true;
                } else {
                    log("Prism still didn't close; the setting is added when it does");
                    p.onExit().get(24, TimeUnit.HOURS);
                }
            }
            Thread.sleep(500);
        }
        log("instance.cfg: " + Prism.patch(instanceCfg, globalCfg));
        if (reopen) {
            log("opening Prism again");
            final ProcessBuilder pb = new ProcessBuilder(prismExe.toString());
            final File dir = prismExe.getParent() == null ? null : prismExe.getParent().toFile();
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
            p.destroy(); // SIGTERM: Qt apps close normally.
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

    /** Games (Java processes) Prism is still running, besides the one that just closed. */
    private static long runningGames(ProcessHandle prism) {
        return prism.children().filter(c -> c.isAlive() && c.info().command()
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
