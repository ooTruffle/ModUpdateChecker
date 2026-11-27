package com.ootruffle.modupdatechecker.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mods Polyfrost's OneClient launcher keeps up to date itself: the ones in its bundles (HUD,
 * QoL, Performance...). OneClient applies bundle updates when it launches the game, so updating one
 * of these here would leave two versions of it after OneClient's next update. Everything else in a
 * OneClient cluster (mods the player added) is checked as usual.
 * <p>
 * A cluster is {@code <data>/clusters/<name>}, and OneClient keeps each bundle as a Modrinth pack in
 * {@code <data>/metadata/caches/bundles/bundles/*.mrpack}; its {@code modrinth.index.json} lists the
 * bundle's files with their SHA-1 hashes and download links.
 */
public final class OneClientBundles {

    private static final Pattern MODRINTH_PROJECT = Pattern.compile("cdn\\.modrinth\\.com/data/([A-Za-z0-9]+)/");

    /** Nothing managed: not a OneClient cluster, or its bundles couldn't be read. */
    public static final OneClientBundles NONE = new OneClientBundles(Set.of(), Set.of(), Set.of());

    private final Set<String> sha1;
    private final Set<String> fileNames;
    private final Set<String> modrinthProjects;

    private OneClientBundles(Set<String> sha1, Set<String> fileNames, Set<String> modrinthProjects) {
        this.sha1 = sha1;
        this.fileNames = fileNames;
        this.modrinthProjects = modrinthProjects;
    }

    /** Whether OneClient updates this jar itself (by its SHA-1, lower case, or its file name). */
    public boolean manages(String sha1Hex, String fileName) {
        return (sha1Hex != null && sha1.contains(sha1Hex.toLowerCase(Locale.ROOT)))
                || (fileName != null && fileNames.contains(fileName.toLowerCase(Locale.ROOT)));
    }

    /** Whether a bundle has this Modrinth project (catches a jar a bundle update hasn't replaced yet). */
    public boolean managesProject(String projectId) {
        return projectId != null && modrinthProjects.contains(projectId);
    }

    public boolean isEmpty() {
        return sha1.isEmpty();
    }

    /** OneClient's data folder for a cluster's game folder, or null if it isn't one. */
    public static Path dataDir(Path gameDir) {
        final Path clusters = gameDir.toAbsolutePath().normalize().getParent();
        if (clusters == null || clusters.getFileName() == null
                || !clusters.getFileName().toString().equalsIgnoreCase("clusters")) return null;
        final Path data = clusters.getParent();
        if (data == null || !Files.isDirectory(bundlesDir(data))) return null;
        return data;
    }

    private static Path bundlesDir(Path data) {
        return data.resolve("metadata").resolve("caches").resolve("bundles").resolve("bundles");
    }

    /** The bundled mods for this Minecraft version, or {@link #NONE}. Never throws. */
    public static OneClientBundles load(Path gameDir, String minecraftVersion) {
        try {
            final Path data = dataDir(gameDir);
            if (data == null) return NONE;
            final Set<String> sha1 = new HashSet<>();
            final Set<String> names = new HashSet<>();
            final Set<String> projects = new HashSet<>();
            try (DirectoryStream<Path> packs = Files.newDirectoryStream(bundlesDir(data), "*.mrpack")) {
                for (Path pack : packs) {
                    try {
                        read(pack, minecraftVersion, sha1, names, projects);
                    } catch (Exception e) {
                        Env.log.warn("Couldn't read OneClient bundle {}: {}", pack.getFileName(), e.toString());
                    }
                }
            }
            if (sha1.isEmpty()) return NONE;
            return new OneClientBundles(sha1, names, projects);
        } catch (Exception e) {
            Env.log.warn("Couldn't read OneClient's bundles: {}", e.toString());
            return NONE;
        }
    }

    private static void read(Path pack, String minecraftVersion, Set<String> sha1, Set<String> names, Set<String> projects) throws Exception {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            final ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) return;
            final String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final Map<String, Object> index = Json.obj(Json.parse(text));
            if (!minecraftVersion.equals(Json.str(Json.obj(index, "dependencies"), "minecraft"))) return;
            for (Object f : Json.arr(index, "files")) {
                final Map<String, Object> file = Json.obj(f);
                final String path = Json.str(file, "path").replace('\\', '/');
                if (!path.startsWith("mods/")) continue;
                final String hash = Json.str(Json.obj(file, "hashes"), "sha1");
                if (!hash.isEmpty()) sha1.add(hash.toLowerCase(Locale.ROOT));
                names.add(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
                for (Object url : Json.arr(file, "downloads")) {
                    if (!(url instanceof String s)) continue;
                    final Matcher m = MODRINTH_PROJECT.matcher(s);
                    if (m.find()) projects.add(m.group(1));
                }
            }
        }
    }
}
