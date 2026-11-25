package com.ootruffle.modupdatechecker.install;

import com.ootruffle.modupdatechecker.Config;
import com.ootruffle.modupdatechecker.ModStatus;
import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.JarScanner;
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

    /** Deletes this mod's jar when the game closes. Returns false if it can't (dev runs). */
    public static boolean removeSelfOnExit() {
        if (Env.selfPaths.size() != 1 || !Files.isRegularFile(Env.selfPaths.get(0))) return false;
        final String self = Env.selfPaths.get(0).toString();
        synchronized (pending) {
            // Removing wins over an update of this mod.
            pending.removeIf(p -> p[1].equals(self));
            pending.add(new String[] {DELETE, self, self});
            try {
                Files.createDirectories(stagedDir());
            } catch (Exception ignored) {
            }
            writePending();
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Installer::startApplier, "ModUpdateChecker-Apply"));
            }
        }
        Env.log.info("Mod Update Checker will be removed when the game closes");
        return true;
    }

    public static boolean selfRemovalPending() {
        synchronized (pending) {
            for (String[] p : pending) if (DELETE.equals(p[0])) return true;
        }
        return false;
    }

    public static void cancelSelfRemoval() {
        synchronized (pending) {
            pending.removeIf(p -> DELETE.equals(p[0]));
            writePending();
        }
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
            final Path staged = fetch(mod);
            Applier.apply(staged, mod.jar, mod.jar.resolveSibling(safeName(mod.downloadName)));
            mod.state = ModStatus.State.DOWNLOADED;
            return true;
        } catch (Exception e) {
            mod.error = "Update failed: " + e.getMessage();
            return false;
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
        final Path staged = fetch(mod);
        final Path newJar = mod.jar.resolveSibling(name);
        synchronized (pending) {
            pending.removeIf(p -> p[1].equals(mod.jar.toString()));
            pending.add(new String[] {staged.toString(), mod.jar.toString(), newJar.toString()});
            writePending();
            if (!hookAdded) {
                hookAdded = true;
                Runtime.getRuntime().addShutdownHook(new Thread(Installer::startApplier, "ModUpdateChecker-Apply"));
            }
        }
        Env.log.info("Downloaded {} {}; it's installed when the game closes", mod.name, mod.latestVersion);
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
