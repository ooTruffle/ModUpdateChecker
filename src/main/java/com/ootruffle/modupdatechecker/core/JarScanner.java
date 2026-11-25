package com.ootruffle.modupdatechecker.core;

import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.UpdateChecker;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the mods in the mods folder by reading each jar's {@code fabric.mod.json}, for the
 * pre-launch check (in the game, Fabric's own list is used). Like the game, it only looks at the
 * jars directly in {@code mods}, not the ones bundled inside them.
 */
public final class JarScanner {

    private static final Set<String> BUILTIN = Set.of("java", "minecraft", "fabricloader");

    private JarScanner() {}

    public static List<ModStatus> scan() {
        final List<ModStatus> list = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final Path dir = Env.modsDir();
        if (!Files.isDirectory(dir)) {
            Env.log.warn("No mods folder at {}", dir);
            return list;
        }
        try (var files = Files.list(dir)) {
            for (Path jar : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(jar) || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                try {
                    final ModStatus m = read(jar);
                    if (m != null && !BUILTIN.contains(m.id) && seen.add(m.id)) list.add(m);
                } catch (Exception e) {
                    Env.log.warn("Couldn't read {}: {}", jar.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            Env.log.warn("Couldn't list {}: {}", dir, e.toString());
        }
        return list;
    }

    /** The mod in [jar], or null if it isn't a Fabric/Ornithe mod. */
    public static ModStatus read(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            final ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            final String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final Map<String, Object> o = Json.obj(Json.parse(text));
            final String id = Json.str(o, "id");
            if (id.isEmpty()) return null;
            final String name = Json.str(o, "name").isEmpty() ? id : Json.str(o, "name");
            final Map<String, String> contact = new HashMap<>();
            for (Map.Entry<String, Object> e : Json.obj(o, "contact").entrySet()) {
                if (e.getValue() instanceof String s) contact.put(e.getKey(), s);
            }
            final Object custom = Json.obj(Json.obj(o, "custom"), Env.MOD_ID).get("github");
            final String repo = UpdateChecker.githubRepo(id, custom instanceof String s ? s : null, contact);
            return new ModStatus(id, name, Json.str(o, "version"), jar.toAbsolutePath().normalize(), repo);
        }
    }
}
