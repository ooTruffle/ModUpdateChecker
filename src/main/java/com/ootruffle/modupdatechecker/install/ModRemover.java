package com.ootruffle.modupdatechecker.install;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.ModProblems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes unwanted jars (see {@link ModProblems}) out of the mods folder, into {@code removed-mods}
 * next to it, so they can be put back. A jar the game has open is locked on Windows: that one is
 * moved when the game closes, by the same helper that installs updates.
 */
public final class ModRemover {

    /** Jars taken out (or waiting for the game to close) this run. */
    private static final Set<Path> removed = ConcurrentHashMap.newKeySet();
    private static final Set<Path> later = ConcurrentHashMap.newKeySet();

    private ModRemover() {}

    public static Path removedDir() {
        return Env.gameDir.resolve("removed-mods");
    }

    public static boolean isRemoved(Path jar) {
        return removed.contains(jar);
    }

    /** How many jars were taken out this run (moved, or waiting for the game to close). */
    public static int removedCount() {
        return removed.size();
    }

    /** How many removals wait for the game to close. */
    public static int waiting() {
        return later.size();
    }

    /** The jars in [jars] not removed yet. */
    public static List<ModProblems.Jar> remaining(Collection<ModProblems.Jar> jars) {
        final List<ModProblems.Jar> out = new ArrayList<>();
        for (ModProblems.Jar j : jars) if (!removed.contains(j.path)) out.add(j);
        return out;
    }

    /** Every extra copy (all but the one kept) of each duplicated mod, not removed yet. */
    public static List<ModProblems.Jar> extraCopies(ModProblems.Result result, boolean includeNameOnly) {
        final List<ModProblems.Jar> out = new ArrayList<>();
        for (ModProblems.Group g : result.duplicates) {
            if (g.byNameOnly && !includeNameOnly) continue;
            out.addAll(remaining(g.extra()));
        }
        return out;
    }

    /** How many problems the last scan found that aren't dealt with yet (one per duplicated mod). */
    public static int problemCount() {
        final ModProblems.Result r = ModProblems.current();
        int n = remaining(r.otherLoaders).size() + remaining(r.replaced).size();
        for (ModProblems.Group g : r.duplicates) if (!remaining(g.extra()).isEmpty()) n++;
        return n;
    }

    /** Removes [jar]. Returns true if it's gone now, false if it goes when the game closes (or couldn't). */
    public static synchronized boolean remove(ModProblems.Jar jar) {
        if (removed.contains(jar.path)) return !later.contains(jar.path);
        final Path target = uniqueTarget(jar.fileName());
        try {
            Files.createDirectories(target.getParent());
            Files.move(jar.path, target);
            removed.add(jar.path);
            Env.log.info("Moved {} to {}", jar.fileName(), target.getParent());
            return true;
        } catch (Exception e) {
            if (!Files.exists(jar.path)) {
                removed.add(jar.path);
                return true;
            }
            if (!Env.inGame) {
                // Before launch nothing should hold it: something else has it open.
                Env.log.warn("Couldn't move {}: {}", jar.fileName(), e.toString());
                return false;
            }
            Installer.moveOnExit(jar.path, target);
            removed.add(jar.path);
            later.add(jar.path);
            Env.log.info("{} is in use; it's moved to {} when the game closes", jar.fileName(), target.getParent());
            return false;
        }
    }

    /** Removes each of [jars], returning how many are gone now. */
    public static int removeAll(Collection<ModProblems.Jar> jars) {
        int now = 0;
        for (ModProblems.Jar j : jars) if (remove(j)) now++;
        return now;
    }

    private static Path uniqueTarget(String name) {
        Path target = removedDir().resolve(name);
        for (int i = 1; Files.exists(target) || Installer.isMoveTarget(target); i++) {
            target = removedDir().resolve(i + "-" + name);
        }
        return target;
    }
}
