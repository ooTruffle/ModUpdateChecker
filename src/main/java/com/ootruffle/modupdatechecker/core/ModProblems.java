package com.ootruffle.modupdatechecker.core;

import com.ootruffle.modupdatechecker.UpdateChecker;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Problems in the mods folder (what Duplicate Mod Warning used to find): several copies of one mod,
 * jars made for another loader (Ornithe never loads them), and mods this one replaces. Only uses
 * the JDK, so the pre-launch check finds them too, and can move them out before anything locks them.
 */
public final class ModProblems {

    /** Mods made obsolete by this one (their features are part of it now). */
    public static final Map<String, String> REPLACED = Map.of("duplicatemodwarning", "Duplicate Mod Warning");

    private static final Set<String> BUILTIN = Set.of("java", "minecraft", "fabricloader");

    /** One jar in the mods folder. */
    public static final class Jar {
        public final Path path;
        /** From fabric.mod.json (or mcmod.info); null for jars without one. */
        public final String id;
        public final String name;
        public final String version;
        public final String hash;
        /** The other loader it was made for, or null for a Fabric/Ornithe mod or plain library. */
        public final String otherLoader;
        public final long modified;

        Jar(Path path, String id, String name, String version, String hash, String otherLoader, long modified) {
            this.path = path;
            this.id = id;
            this.name = name;
            this.version = version;
            this.hash = hash;
            this.otherLoader = otherLoader;
            this.modified = modified;
        }

        public String fileName() {
            return path.getFileName().toString();
        }

        public String label() {
            return name != null ? name : id != null ? id : fileName();
        }
    }

    /** Copies of one mod, the one to keep first. */
    public static final class Group {
        public final String name;
        public final List<Jar> jars;
        /** Every copy is the same file. */
        public final boolean identical;
        /** Only matched by name (different mod ids): worth a look before removing. */
        public final boolean byNameOnly;

        Group(String name, List<Jar> jars, boolean identical, boolean byNameOnly) {
            this.name = name;
            this.jars = jars;
            this.identical = identical;
            this.byNameOnly = byNameOnly;
        }

        public Jar keep() {
            return jars.get(0);
        }

        public List<Jar> extra() {
            return jars.subList(1, jars.size());
        }
    }

    public static final class Result {
        public final List<Group> duplicates;
        public final List<Jar> otherLoaders;
        /** Installed mods that this one replaces. */
        public final List<Jar> replaced;

        Result(List<Group> duplicates, List<Jar> otherLoaders, List<Jar> replaced) {
            this.duplicates = duplicates;
            this.otherLoaders = otherLoaders;
            this.replaced = replaced;
        }

        public boolean isEmpty() {
            return duplicates.isEmpty() && otherLoaders.isEmpty() && replaced.isEmpty();
        }

        /** The copy kept in place of [jar], if [jar] is an extra copy of a mod; else null. */
        public Path keptInsteadOf(Path jar) {
            for (Group g : duplicates) {
                for (Jar extra : g.extra()) if (extra.path.equals(jar)) return g.keep().path;
            }
            return null;
        }
    }

    private static final Result EMPTY = new Result(List.of(), List.of(), List.of());
    private static volatile Result current = EMPTY;

    private ModProblems() {}

    /** What the last scan found. */
    public static Result current() {
        return current;
    }

    /** Forgets the last scan (the check is turned off). */
    public static Result clear() {
        current = EMPTY;
        return EMPTY;
    }

    /** Scans the mods folder (hashing every jar), remembering the result. */
    public static Result scan() {
        final Path dir = Env.modsDir();
        final List<Jar> mods = new ArrayList<>();
        final List<Jar> other = new ArrayList<>();
        final List<Jar> replaced = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    final String lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!Files.isRegularFile(f) || !(lower.endsWith(".jar") || lower.endsWith(".zip"))) continue;
                    final Jar jar = read(f.toAbsolutePath().normalize());
                    if (jar == null) continue;
                    if (jar.otherLoader != null) other.add(jar);
                    else if (jar.id != null && REPLACED.containsKey(jar.id)) replaced.add(jar);
                    else if (jar.id == null || !BUILTIN.contains(jar.id)) mods.add(jar);
                }
            } catch (Exception e) {
                Env.log.warn("Couldn't list {}: {}", dir, e.toString());
            }
        }
        other.sort(Comparator.comparing(j -> j.fileName().toLowerCase(Locale.ROOT)));
        final Result result = new Result(group(mods), other, replaced);
        current = result;
        return result;
    }

    /**
     * Whether copy [a] is the one to keep over [b]: the higher version when both versions can be
     * ordered, otherwise the newer file. Positive for a, negative for b.
     */
    public static int prefer(String versionA, long modifiedA, String versionB, long modifiedB) {
        if (versionA != null && versionB != null) {
            // Versions.compare(x, y) is negative when y is newer; only trust it when it agrees both ways.
            final int ab = Versions.compare(versionA, versionB);
            final int ba = Versions.compare(versionB, versionA);
            if (ab > 0 && ba < 0) return 1;
            if (ab < 0 && ba > 0) return -1;
        }
        return Long.compare(modifiedA, modifiedB);
    }

    /** Names a browser or file manager gives a second copy: "mod (1).jar", "mod - Copy.jar". */
    private static final java.util.regex.Pattern COPY_NAME = java.util.regex.Pattern.compile("(?i)\\(\\d+\\)|copy|kopie|kopia|copie");

    /**
     * The copy to keep first: the highest version. Identical copies keep the one that doesn't look
     * like a copy ("mod.jar" over "mod (1).jar"), then the older file (the original).
     */
    private static final Comparator<Jar> KEEP_FIRST = (a, b) -> {
        final int byVersion = prefer(b.version, 0, a.version, 0);
        if (byVersion != 0) return byVersion;
        if (a.hash.equals(b.hash)) {
            final int copy = Boolean.compare(COPY_NAME.matcher(a.fileName()).find(), COPY_NAME.matcher(b.fileName()).find());
            if (copy != 0) return copy;
            final int older = Long.compare(a.modified, b.modified);
            return older != 0 ? older : a.fileName().compareTo(b.fileName());
        }
        return Long.compare(b.modified, a.modified);
    };

    /** Jars sharing a hash, mod id or name are one group (linked transitively). */
    private static List<Group> group(List<Jar> jars) {
        final int n = jars.size();
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (match(jars.get(i), jars.get(j)) != 0) parent[find(parent, i)] = find(parent, j);
            }
        }
        final Map<Integer, List<Jar>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(jars.get(i));
        final List<Group> out = new ArrayList<>();
        for (List<Jar> g : groups.values()) {
            if (g.size() < 2) continue;
            g.sort(KEEP_FIRST);
            boolean identical = true;
            boolean byName = true;
            for (int i = 0; i < g.size(); i++) {
                identical &= g.get(i).hash.equals(g.get(0).hash);
                for (int j = i + 1; j < g.size(); j++) if (match(g.get(i), g.get(j)) == 1) byName = false;
            }
            out.add(new Group(g.get(0).label(), Collections.unmodifiableList(g), identical, byName && !identical));
        }
        out.sort(Comparator.comparing(g -> g.name.toLowerCase(Locale.ROOT)));
        return out;
    }

    /** 0: different mods; 1: same file or mod id; 2: same name only. */
    private static int match(Jar a, Jar b) {
        if (a.hash.equals(b.hash) || (a.id != null && a.id.equals(b.id))) return 1;
        if (a.name != null && b.name != null) {
            final String na = normalize(a.name);
            if (!na.isEmpty() && na.equals(normalize(b.name))) return 2;
        }
        return 0;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Jar read(Path file) {
        final String hash;
        long modified = 0;
        try {
            hash = UpdateChecker.hash(file, "SHA-1");
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            Env.log.warn("Couldn't read {}: {}", file.getFileName(), e.toString());
            return null;
        }
        String id = null, name = null, version = null, other = null;
        try (JarFile zip = new JarFile(file.toFile())) {
            final ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                final Map<String, Object> o = Json.obj(Json.parse(text(zip, fabric)));
                id = blankToNull(Json.str(o, "id"));
                name = blankToNull(Json.str(o, "name"));
                version = blankToNull(Json.str(o, "version"));
            } else {
                other = otherLoader(zip);
                final ZipEntry forge = zip.getEntry("mcmod.info");
                if (forge != null) {
                    // An array, or {"modList": [...]}.
                    final Object root = Json.parse(text(zip, forge));
                    final List<Object> list = root instanceof List<?> ? Json.arr(root) : Json.arr(Json.obj(root), "modList");
                    if (!list.isEmpty()) {
                        final Map<String, Object> o = Json.obj(list.get(0));
                        id = blankToNull(Json.str(o, "modid"));
                        name = blankToNull(Json.str(o, "name"));
                        version = blankToNull(Json.str(o, "version"));
                    }
                }
            }
        } catch (Exception ignored) {
            // Not a readable jar, or odd metadata: the hash still finds identical copies.
        }
        return new Jar(file, id, name, version, hash, other, modified);
    }

    /** Which other loader a jar without fabric.mod.json was made for, or null for a plain library. */
    private static String otherLoader(JarFile zip) throws Exception {
        if (zip.getEntry("mcmod.info") != null) return "Forge";
        if (zip.getEntry("META-INF/neoforge.mods.toml") != null) return "NeoForge";
        if (zip.getEntry("META-INF/mods.toml") != null) return "Forge 1.13+";
        if (zip.getEntry("quilt.mod.json") != null) return "Quilt";
        if (zip.getEntry("litemod.json") != null) return "LiteLoader";
        if (zip.getEntry("riftmod.json") != null) return "Rift";
        if (zip.getEntry("addon.json") != null) return "LabyMod addon";
        // Forge mods without mcmod.info (coremods, tweakers like Essential's loader).
        final Manifest manifest = zip.getManifest();
        if (manifest != null) {
            final Attributes a = manifest.getMainAttributes();
            if (a.getValue("FMLCorePlugin") != null || a.getValue("TweakClass") != null || a.getValue("FMLAT") != null) return "Forge";
        }
        return null;
    }

    private static String text(JarFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Logs what [result] found (the pre-launch check's output, and the game log). */
    public static void log(Result result) {
        for (Group g : result.duplicates) {
            Env.log.warn("{} is installed {} times{}:", g.name, g.jars.size(), g.identical ? " (identical copies)" : "");
            for (int i = 0; i < g.jars.size(); i++) {
                final Jar j = g.jars.get(i);
                Env.log.warn("    {} {}{}", i == 0 ? "keep  " : "remove", j.fileName(), j.version != null ? " (" + j.version + ")" : "");
            }
        }
        for (Jar j : result.otherLoaders) Env.log.warn("{} is made for {}, so it's never loaded", j.fileName(), j.otherLoader);
        for (Jar j : result.replaced) {
            Env.log.warn("{} isn't needed any more: Mod Update Checker does what it did", REPLACED.get(j.id));
        }
    }
}
