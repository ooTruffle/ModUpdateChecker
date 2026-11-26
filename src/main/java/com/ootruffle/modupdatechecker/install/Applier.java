package com.ootruffle.modupdatechecker.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Swaps downloaded mod jars in once the game has closed. Runs as its own small Java process (see
 * {@link Installer}) and uses only the JDK. Arguments: the game's process ID and the pending list
 * (one "downloaded jar, old jar, new jar" line per update, tab separated).
 * <p>
 * If an old jar can't be removed, its new jar is taken back out of the mods folder, so two versions
 * of a mod are never loaded together.
 */
public final class Applier {

    static final String PENDING_FILE = "pending.tsv";
    private static final int DELETE_ATTEMPTS = 60;

    private Applier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("usage: Applier <pid> <pending file>");
            System.exit(2);
        }
        final long pid = Long.parseLong(args[0]);
        final Path pendingFile = Paths.get(args[1]);
        log("waiting for the game (pid " + pid + ") to close");
        ProcessHandle.of(pid).ifPresent(game -> {
            try {
                game.onExit().get(10, TimeUnit.MINUTES);
            } catch (Exception e) {
                log("  gave up waiting: " + e);
            }
        });
        Thread.sleep(500);
        final List<String> lines = Files.readAllLines(pendingFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            final String[] p = line.split("\t");
            if (p.length != 3 && p.length != 4) continue;
            if (Installer.DELETE.equals(p[0])) {
                try {
                    delete(Paths.get(p[1]));
                    log("removed " + Paths.get(p[1]).getFileName());
                } catch (Exception e) {
                    log("not removed " + Paths.get(p[1]).getFileName() + ": " + e);
                }
                continue;
            }
            try {
                apply(Paths.get(p[0]), Paths.get(p[1]), Paths.get(p[2]));
                log("installed " + Paths.get(p[2]).getFileName());
            } catch (Exception e) {
                log("not installed " + Paths.get(p[2]).getFileName() + ": " + e);
                continue;
            }
            if (p.length == 4) {
                try {
                    applyMeta(Paths.get(p[3]), Paths.get(p[1]), Paths.get(p[2]));
                    log("  updated Prism's metadata");
                } catch (Exception e) {
                    log("  Prism's metadata not updated: " + e);
                }
            }
        }
        Files.deleteIfExists(pendingFile);
    }

    public static void apply(Path staged, Path oldJar, Path newJar) throws IOException, InterruptedException {
        if (!Files.isRegularFile(staged)) throw new IOException("the download is missing: " + staged);
        final boolean sameName = oldJar.toAbsolutePath().normalize().equals(newJar.toAbsolutePath().normalize());
        if (!sameName) {
            final com.ootruffle.modupdatechecker.ModStatus update;
            try {
                update = com.ootruffle.modupdatechecker.core.JarScanner.read(staged);
            } catch (Exception e) {
                throw new IOException("the download can't be read: " + e, e);
            }
            Installer.checkTarget(oldJar, newJar, update == null ? null : update.id);
        }
        if (!sameName) {
            // A .tmp name first: Fabric only loads *.jar, so a half-copied file is never picked up.
            final Path tmp = newJar.resolveSibling(newJar.getFileName() + ".tmp");
            Files.copy(staged, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, newJar, StandardCopyOption.REPLACE_EXISTING);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                if (sameName) {
                    Files.copy(staged, newJar, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(oldJar);
                }
                break;
            } catch (IOException e) {
                if (attempt >= DELETE_ATTEMPTS) {
                    if (!sameName) Files.deleteIfExists(newJar);
                    throw new IOException("the old jar is still in use: " + e, e);
                }
                Thread.sleep(500);
            }
        }
        Files.deleteIfExists(staged);
    }

    /**
     * Puts the staged Prism metadata (see {@link com.ootruffle.modupdatechecker.core.PrismMeta}) for
     * the installed [newJar] in the mods folder's .index, replacing the old jar's.
     */
    static void applyMeta(Path meta, Path oldJar, Path newJar) throws IOException {
        final String toml = Files.readString(meta, StandardCharsets.UTF_8);
        final String file = meta.getFileName().toString();
        com.ootruffle.modupdatechecker.core.PrismMeta.install(newJar.getParent(), toml,
                oldJar.getFileName().toString(), newJar.getFileName().toString(),
                file.substring(0, file.length() - ".pw.toml".length()));
        Files.deleteIfExists(meta);
    }

    /** Deletes a jar the game has let go of, retrying while it's still locked. */
    static void delete(Path jar) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.deleteIfExists(jar);
                return;
            } catch (IOException e) {
                if (attempt >= DELETE_ATTEMPTS) throw new IOException("the jar is still in use: " + e, e);
                Thread.sleep(500);
            }
        }
    }

    private static void log(String message) {
        System.out.println(LocalDateTime.now() + " " + message);
    }
}
