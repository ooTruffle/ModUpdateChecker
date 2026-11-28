package com.ootruffle.modupdatechecker.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quit in the pre-launch window when the launcher can't cancel a launch (OneClient starts the game
 * even when its pre-launch command fails): the window leaves this marker, and the mod closes the
 * game as soon as it starts, before Minecraft loads (see
 * {@link com.ootruffle.modupdatechecker.QuitOnStart}).
 * <p>
 * The marker is {@code config/modupdatechecker/quit-launch} in the game folder and holds the mods
 * folder it's for, since OneClient clusters can share one game folder. It only counts for a couple
 * of minutes, so a launch that never happened can't close a later game.
 */
public final class QuitMarker {

    private static final long MAX_AGE_MILLIS = 120_000;

    private QuitMarker() {}

    private static Path file(Path gameDir) {
        return gameDir.resolve("config").resolve(Env.MOD_ID).resolve("quit-launch");
    }

    /** Leaves the marker for the game about to start from [modsDir]. */
    public static void write(Path gameDir, Path modsDir) throws Exception {
        final Path f = file(gameDir);
        Files.createDirectories(f.getParent());
        Files.writeString(f, modsDir.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
    }

    /** Whether the game starting from [modsDir] should close; removes the marker either way. */
    public static boolean take(Path gameDir, Path modsDir) {
        final Path f = file(gameDir);
        try {
            if (!Files.isRegularFile(f)) return false;
            final boolean fresh = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < MAX_AGE_MILLIS;
            final String forMods = Files.readString(f, StandardCharsets.UTF_8).trim();
            final boolean ours = forMods.isEmpty() || Path.of(forMods).equals(modsDir.toAbsolutePath().normalize());
            if (!ours && fresh) return false; // Another cluster's, still on its way.
            Files.deleteIfExists(f);
            return fresh && ours;
        } catch (Exception e) {
            return false;
        }
    }
}
