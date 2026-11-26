package com.ootruffle.modupdatechecker.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prism Launcher's mod metadata: one packwiz-style {@code mods/.index/<name>.pw.toml} file per mod,
 * matched to its jar by {@code filename}. Without one, Prism lists the mod's provider as "Unknown"
 * and can't update it, so a Modrinth update gets a file pointing at its new jar (replacing the old
 * jar's file). Uses only the JDK, since the {@link com.ootruffle.modupdatechecker.install.Applier}
 * writes it.
 */
public final class PrismMeta {

    private static final Pattern FILENAME = Pattern.compile("^\\s*filename\\s*=\\s*(['\"])(.*)\\1\\s*$");

    private PrismMeta() {}

    /** Whether [gameDir] is a Prism (or MultiMC) instance's game folder. */
    public static boolean isPrismInstance(Path gameDir) {
        final Path instance = gameDir == null ? null : gameDir.getParent();
        return instance != null && Files.isRegularFile(instance.resolve("mmc-pack.json"));
    }

    /** What Prism writes for a mod installed from Modrinth. */
    public static String modrinthToml(String name, String filename, String url, String sha512,
                                      String projectId, String versionId,
                                      List<String> loaders, List<String> gameVersions, String releaseType) {
        final StringBuilder sb = new StringBuilder();
        sb.append("filename = ").append(str(filename)).append('\n');
        sb.append("name = ").append(str(name)).append('\n');
        sb.append("side = 'both'\n");
        if (loaders != null && !loaders.isEmpty()) sb.append("x-prismlauncher-loaders = ").append(list(loaders)).append('\n');
        if (gameVersions != null && !gameVersions.isEmpty()) sb.append("x-prismlauncher-mc-versions = ").append(list(gameVersions)).append('\n');
        if (releaseType != null && !releaseType.isEmpty()) sb.append("x-prismlauncher-release-type = ").append(str(releaseType)).append('\n');
        sb.append('\n');
        sb.append("[download]\n");
        sb.append("hash = ").append(str(sha512)).append('\n');
        sb.append("hash-format = 'sha512'\n");
        sb.append("mode = 'url'\n");
        sb.append("url = ").append(str(url)).append('\n');
        sb.append('\n');
        sb.append("[update.modrinth]\n");
        sb.append("mod-id = ").append(str(projectId)).append('\n');
        sb.append("version = ").append(str(versionId)).append('\n');
        return sb.toString();
    }

    /**
     * Puts [toml] (for the jar [newName]) in [modsDir]'s {@code .index}, replacing any file for
     * [oldName] or [newName]: the first one found keeps its name, others are removed. [fallbackName]
     * names the file when there was none.
     */
    public static void install(Path modsDir, String toml, String oldName, String newName, String fallbackName) throws IOException {
        final Path index = modsDir.resolve(".index");
        Files.createDirectories(index);
        Path target = null;
        try (var files = Files.list(index)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().endsWith(".pw.toml") || !Files.isRegularFile(p)) continue;
                final String f = filename(p);
                if (f == null || !(f.equals(oldName) || f.equals(newName))) continue;
                if (target == null) {
                    target = p;
                } else {
                    Files.deleteIfExists(p);
                }
            }
        }
        if (target == null) target = index.resolve(safe(fallbackName) + ".pw.toml");
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, toml, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The {@code filename} a .pw.toml file points at, or null. */
    static String filename(Path toml) {
        try {
            for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("[")) return null; // Only top-level keys.
                final Matcher m = FILENAME.matcher(line);
                if (m.matches()) {
                    final String v = m.group(2);
                    return m.group(1).equals("'") ? v : v.replace("\\\"", "\"").replace("\\\\", "\\");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String safe(String name) {
        final String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return s.isEmpty() ? "mod" : s;
    }

    /** A TOML basic string. */
    private static String str(String s) {
        final StringBuilder sb = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String list(List<String> items) {
        final StringBuilder sb = new StringBuilder("[ ");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(str(items.get(i)));
        }
        return sb.append(" ]").toString();
    }
}
