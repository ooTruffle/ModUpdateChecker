package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Sets up {@link Env} in the game: paths, versions and the installed mods come from Fabric. */
final class FabricEnv {

    private static final Set<String> BUILTIN = Set.of("java", "minecraft", "fabricloader");

    private FabricEnv() {}

    static void install() {
        final FabricLoader loader = FabricLoader.getInstance();
        Env.gameDir = loader.getGameDir().toAbsolutePath().normalize();
        final String modsFolder = System.getProperty("fabric.modsFolder");
        if (modsFolder != null && !modsFolder.isBlank()) {
            Env.modsDirOverride = Env.gameDir.resolve(modsFolder).toAbsolutePath().normalize();
        }
        Env.inGame = true;
        Env.minecraftVersion = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("1.8.9");
        Env.log = new Env.Log() {
            @Override
            public void info(String message, Object... args) {
                ModUpdateChecker.LOGGER.info(message, args);
            }

            @Override
            public void warn(String message, Object... args) {
                ModUpdateChecker.LOGGER.warn(message, args);
            }
        };
        final List<Path> self = new ArrayList<>();
        loader.getModContainer(Env.MOD_ID).ifPresent(c -> {
            for (Path p : c.getOrigin().getPaths()) self.add(p.toAbsolutePath().normalize());
        });
        Env.selfPaths = self;
        Env.scanner = FabricEnv::scan;
    }

    /** The mods loaded from jars in the mods folder (not built-in or bundled ones). */
    private static List<ModStatus> scan() {
        final Path modsDir = Env.modsDir().toAbsolutePath().normalize();
        final Map<Path, ModStatus> byJar = new LinkedHashMap<>();
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            final ModMetadata meta = container.getMetadata();
            if (BUILTIN.contains(meta.getId())) continue;
            final ModOrigin origin;
            try {
                origin = container.getOrigin();
            } catch (Throwable e) {
                continue;
            }
            if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) continue; // Nested or built-in.
            final Path jar = origin.getPaths().get(0).toAbsolutePath().normalize();
            if (!jar.startsWith(modsDir) || !Files.isRegularFile(jar)
                    || !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (byJar.containsKey(jar)) continue;
            final ModStatus mod = new ModStatus(meta.getId(), meta.getName(), meta.getVersion().getFriendlyString(), jar, githubRepo(meta));
            final List<String> authors = new ArrayList<>();
            try {
                for (net.fabricmc.loader.api.metadata.Person p : meta.getAuthors()) authors.add(p.getName());
            } catch (Throwable ignored) {
            }
            mod.repoGuesses = UpdateChecker.repoGuesses(meta.getId(), meta.getName(), authors);
            byJar.put(jar, mod);
        }
        return new ArrayList<>(byJar.values());
    }

    private static String githubRepo(ModMetadata meta) {
        String custom = null;
        try {
            final CustomValue value = meta.getCustomValue(Env.MOD_ID);
            if (value != null && value.getType() == CustomValue.CvType.OBJECT) {
                final CustomValue gh = value.getAsObject().get("github");
                if (gh != null && gh.getType() == CustomValue.CvType.STRING) custom = gh.getAsString();
            }
        } catch (Throwable ignored) {
        }
        final Map<String, String> contact = new HashMap<>();
        for (String key : new String[] {"sources", "homepage", "issues"}) {
            final Optional<String> url = meta.getContact().get(key);
            url.ifPresent(u -> contact.put(key, u));
        }
        return UpdateChecker.githubRepo(meta.getId(), custom, contact);
    }
}
