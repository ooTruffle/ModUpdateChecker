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
 * The mods that come from Polyfrost's OneClient bundles (HUD, QoL, Performance...), so the
 * updates screen can say so. They're still checked and updated: when a bundled jar is replaced,
 * OneClient switches the bundle's copy off (as a deliberate choice, so bundle updates don't turn it
 * back on) and keeps the new jar.
 * <p>
 * OneClient keeps each bundle as a Modrinth pack in
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

    private static Path bundlesDir(Path data) {
        return data.resolve("metadata").resolve("caches").resolve("bundles").resolve("bundles");
    }

    /** The bundled mods for this Minecraft version, from OneClient's data folder (null: none). Never throws. */
    public static OneClientBundles load(Path data, String minecraftVersion) {
        try {
            if (data == null || !Files.isDirectory(bundlesDir(data))) return NONE;
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
