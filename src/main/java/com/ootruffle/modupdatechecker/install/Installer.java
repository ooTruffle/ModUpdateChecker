package com.ootruffle.modupdatechecker.install;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.JarScanner;
import com.ootruffle.modupdatechecker.core.PrismMeta;
import com.ootruffle.modupdatechecker.core.Versions;
import com.ootruffle.modupdatechecker.UpdateChecker;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Downloads updates into {@code config/modupdatechecker/staged} and, as the game closes, starts
 * {@link Applier} in its own Java process to swap the jars once the game has let go of them
 * (Windows keeps a running game's jars locked).
 */
public final class Installer {

    private static final List<String[]> pending = new ArrayList<>();
    private static boolean hookAdded;

    private Installer() {}

    static Path stagedDir() {
        return Config.dir().resolve("staged");
    }

    static Path pendingFile() {
        return stagedDir().resolve(Applier.PENDING_FILE);
    }

    public static void init() {
        // Anything left from a run whose applier never started is stale: the jars may have changed.
        try {
            if (Files.isDirectory(stagedDir())) {
                try (var files = Files.list(stagedDir())) {
                    for (Path p : (Iterable<Path>) files::iterator) {
                        if (!p.getFileName().toString().equals("update.log")) Files.deleteIfExists(p);
                    }
                }
            }
        } catch (Exception e) {
            Env.log.warn("Couldn't clean old downloads: {}", e.toString());
        }
    }

    /** Marks an entry in the pending list that deletes the jar instead of replacing it. */
    static final String DELETE = "-";

    /** Marks an entry in the pending list that moves the jar (to removed-mods) instead of replacing it. */
    static final String MOVE = ">";

    private static void addHook() {
        if (!hookAdded) {
            hookAdded = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Installer::startApplier, "ModUpdateChecker-Apply"));
        }
    }

    /** Moves [jar] to [target] when the game closes (it's locked until then), instead of any update of it. */
    public static void moveOnExit(Path jar, Path target) {
        synchronized (pending) {
            pending.removeIf(p -> p[1].equals(jar.toString()));
            pending.add(new String[] {MOVE, jar.toString(), target.toString()});
            try {
                Files.createDirectories(stagedDir());
            } catch (Exception ignored) {
            }
            writePending();
            addHook();
        }
    }

    /** Whether some jar is waiting to be moved to [target] when the game closes. */
    public static boolean isMoveTarget(Path target) {
        synchronized (pending) {
            for (String[] p : pending) if (MOVE.equals(p[0]) && p[2].equals(target.toString())) return true;
        }
        return false;
    }

    public static boolean hasPending() {
        synchronized (pending) {
            return !pending.isEmpty();
        }
    }

    /** Downloads [mod]'s update in the background. */
    public static void downloadAsync(ModStatus mod) {
        if (!mod.canDownload()) return;
        mod.state = ModStatus.State.DOWNLOADING;
        mod.progress = 0f;
        final Thread t = new Thread(() -> {
            try {
                download(mod);
                mod.state = ModStatus.State.DOWNLOADED;
            } catch (Exception e) {
                Env.log.warn("Couldn't download {} {}: {}", mod.name, mod.latestVersion, e.toString());
                mod.error = "Download failed: " + e.getMessage();
                mod.state = ModStatus.State.UPDATE_AVAILABLE;
            }
        }, "ModUpdateChecker-Download");
        t.setDaemon(true);
        t.start();
    }

    public static void downloadAll() {
        for (ModStatus m : UpdateChecker.mods()) {
            if (m.wantsUpdate() && m.canDownload()) downloadAsync(m);
        }
    }

    /**
     * Before the game starts (nothing holds the jars): downloads [mod]'s update and swaps it in
     * straight away. Returns false, with {@link ModStatus#error} set, if it couldn't.
     */
    public static boolean installNow(ModStatus mod) {
        if (!mod.canDownload()) return false;
        if (runningFrom(mod.jar)) {
            // This check is running from that very jar (a "mods/*" pre-launch command), which can't
            // be replaced while it runs. The in-game screen installs it when the game closes.
            mod.error = "Update it from the in-game screen (this check is running from its jar)";
            return false;
        }
        try {
            checkTarget(mod.jar, mod.jar.resolveSibling(safeName(mod.downloadName)), mod.id);
            final Path staged = fetch(mod);
            final Path meta = prismMeta(mod, staged);
            final Path newJar = mod.jar.resolveSibling(safeName(mod.downloadName));
            Applier.apply(staged, mod.jar, newJar);
            if (meta != null) Applier.applyMeta(meta, mod.jar, newJar);
            mod.state = ModStatus.State.DOWNLOADED;
            return true;
        } catch (Exception e) {
            mod.error = "Update failed: " + e.getMessage();
            return false;
        }
    }

    /**
     * Makes sure installing over [newJar] can't replace a different mod: a file of that name that
     * isn't [oldJar] must be the same mod (a leftover copy), or it's refused.
     */
    static void checkTarget(Path oldJar, Path newJar, String modId) throws java.io.IOException {
        if (!Files.exists(newJar) || Files.isSameFile(newJar, oldJar)) return;
        String other;
        try {
            final ModStatus read = JarScanner.read(newJar);
            other = read == null ? null : read.id;
        } catch (Exception e) {
            other = null;
        }
        if (modId == null || !modId.equals(other)) {
            throw new java.io.IOException("it would replace " + newJar.getFileName()
                    + (other == null ? ", which isn't this mod" : ", which is a different mod (" + other + ")"));
        }
    }

    /** Whether this code was loaded from [jar]. */
    private static boolean runningFrom(Path jar) {
        try {
            final Path code = Path.of(Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(code) && Files.isSameFile(code, jar);
        } catch (Exception e) {
            return false;
        }
    }

    private static void download(ModStatus mod) throws Exception {
        final String name = safeName(mod.downloadName);
        checkTarget(mod.jar, mod.jar.resolveSibling(name), mod.id); // Before downloading anything.
        final Path staged = fetch(mod);
        final Path meta = prismMeta(mod, staged);
        final Path newJar = mod.jar.resolveSibling(name);
        synchronized (pending) {
            pending.removeIf(p -> p[1].equals(mod.jar.toString()));
            pending.add(meta == null
                    ? new String[] {staged.toString(), mod.jar.toString(), newJar.toString()}
                    : new String[] {staged.toString(), mod.jar.toString(), newJar.toString(), meta.toString()});
            writePending();
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Installer::startApplier, "ModUpdateChecker-Apply"));
            }
        }
        Env.log.info("Downloaded {} {}; it's installed when the game closes", mod.name, mod.latestVersion);
    }

    /**
     * In a Prism instance, for a Modrinth update: writes the Prism metadata for the new jar next to
     * the download (staged/&lt;mod id&gt;.pw.toml), so Prism shows it as from Modrinth once it's
     * installed. Returns the file, or null if there's none to write.
     */
    private static Path prismMeta(ModStatus mod, Path staged) {
        if (mod.source != ModStatus.Source.MODRINTH || mod.modrinthProject == null || mod.modrinthVersion == null
                || !PrismMeta.isPrismInstance(Env.gameDir)) {
            return null;
        }
        try {
            final String toml = PrismMeta.modrinthToml(mod.name, safeName(mod.downloadName), mod.downloadUrl,
                    UpdateChecker.hash(staged, "SHA-512"), mod.modrinthProject, mod.modrinthVersion,
                    mod.modrinthLoaders, mod.modrinthGameVersions, mod.modrinthReleaseType);
            final Path meta = stagedDir().resolve(mod.id.replaceAll("[^A-Za-z0-9._-]", "_") + ".pw.toml");
            Files.writeString(meta, toml, StandardCharsets.UTF_8);
            return meta;
        } catch (Exception e) {
            Env.log.warn("Couldn't prepare Prism's metadata for {}: {}", mod.name, e.toString());
            return null;
        }
    }

    /** Downloads [mod]'s update into the staging folder and checks it, returning the file. */
    private static Path fetch(ModStatus mod) throws Exception {
        final String name = safeName(mod.downloadName);
        Files.createDirectories(stagedDir());
        final Path staged = stagedDir().resolve(mod.id + "-" + name);
        final Path part = staged.resolveSibling(staged.getFileName() + ".part");
        final HttpRequest req = HttpRequest.newBuilder(URI.create(mod.downloadUrl))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", UpdateChecker.USER_AGENT)
                .header("Accept", "application/octet-stream")
                .build();
        final HttpResponse<InputStream> res = httpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new Exception("server answered " + res.statusCode());
        }
        final long total = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            final byte[] buf = new byte[65536];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) mod.progress = (float) done / total;
            }
        }
        if (mod.downloadHash != null) {
            final int colon = mod.downloadHash.indexOf(':');
            final String algorithm = mod.downloadHash.substring(0, colon).equals("sha1") ? "SHA-1" : "SHA-256";
            final String expected = mod.downloadHash.substring(colon + 1).toLowerCase(Locale.ROOT);
            final String actual = UpdateChecker.hash(part, algorithm);
            if (!expected.equals(actual)) {
                Files.deleteIfExists(part);
                throw new Exception("the download's checksum doesn't match");
            }
        }
        try {
            checkSameMod(part, mod.id);
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
        return staged;
    }

    /**
     * Makes sure the download is a Fabric/Ornithe mod with the same id, so a wrong GitHub link (or
     * a Forge release in the same repository) can never put a different mod in the mods folder.
     */
    private static void checkSameMod(Path jar, String expectedId) throws Exception {
        final ModStatus read;
        try {
            read = JarScanner.read(jar);
        } catch (Exception e) {
            throw new Exception("the download isn't a readable jar: " + e.getMessage());
        }
        if (read == null) {
            throw new Exception("the download isn't a Fabric/Ornithe mod (wrong file or wrong repository?)");
        }
        if (!expectedId.equals(read.id)) {
            throw new Exception("the download is a different mod (" + read.id + ", not " + expectedId + ")");
        }
        // A release for another Minecraft version would crash the game on its next launch.
        final Object mc = JarScanner.minecraftDependency(jar);
        if (!Versions.matches(Env.minecraftVersion, mc)) {
            throw new Exception("the download is for Minecraft " + mc + ", not " + Env.minecraftVersion);
        }
    }

    private static java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                .build();
    }

    /** A plain file name (no folders), ending in .jar. */
    private static String safeName(String name) {
        String s = name.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9 ._+()\\[\\]-]", "_");
        if (s.isEmpty() || s.startsWith(".")) s = "update" + s;
        if (!s.toLowerCase(Locale.ROOT).endsWith(".jar")) s += ".jar";
        return s;
    }

    private static void writePending() {
        final StringBuilder sb = new StringBuilder();
        for (String[] p : pending) sb.append(String.join("\t", p)).append('\n');
        try {
            Files.write(pendingFile(), sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            Env.log.warn("Couldn't save the pending updates: {}", e.toString());
        }
    }

    /** Runs as the game exits: starts the applier, which waits for this process to end. */
    private static void startApplier() {
        if (!hasPending()) return;
        try {
            final String java = ProcessHandle.current().info().command().orElse(
                    Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javaw.exe" : "java").toString());
            // This mod's jar (in dev runs, its class and resource folders). A jar is run from a copy:
            // the applier may replace or delete this very jar, which Windows won't allow while
            // it's running from it. (The staging folder, copy included, is cleaned on the next launch.)
            final List<String> parts = new ArrayList<>();
            if (Env.selfPaths.size() == 1 && Files.isRegularFile(Env.selfPaths.get(0))) {
                final Path copy = stagedDir().resolve("applier.jar");
                Files.createDirectories(stagedDir());
                Files.copy(Env.selfPaths.get(0), copy, StandardCopyOption.REPLACE_EXISTING);
                parts.add(copy.toString());
            } else {
                for (Path p : Env.selfPaths) parts.add(p.toString());
                try {
                    final Path code = Path.of(Applier.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
                    if (!parts.contains(code.toString())) parts.add(0, code.toString());
                } catch (Exception ignored) {
                }
            }
            if (parts.isEmpty()) throw new IllegalStateException("can't find this mod's jar");
            Env.log.info("Starting the update installer (classpath {})", parts);
            final String classpath = String.join(System.getProperty("path.separator"), parts);
            final ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath,
                    Applier.class.getName(), Long.toString(ProcessHandle.current().pid()), pendingFile().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(stagedDir().resolve("update.log").toFile()));
            pb.start();
        } catch (Exception e) {
            Env.log.warn("Couldn't start the update installer: {}", e.toString());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
