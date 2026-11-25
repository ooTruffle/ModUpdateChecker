package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.Json;
import com.ootruffle.modupdatechecker.core.Versions;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks each installed mod up on Modrinth, then GitHub. Only uses the JDK (see {@link Env}), so the
 * same checker runs in the game and as a launcher's pre-launch command.
 */
public final class UpdateChecker {

    public static final String USER_AGENT = "ModUpdateChecker/1.0 (Minecraft mod)";
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final Pattern GITHUB_URL = Pattern.compile("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile List<ModStatus> mods = Collections.emptyList();
    private static volatile boolean running;
    private static volatile String summary = "Not checked yet";
    private static volatile long checkedAt;

    private UpdateChecker() {}

    public static List<ModStatus> mods() {
        return mods;
    }

    public static boolean running() {
        return running;
    }

    public static String summary() {
        return summary;
    }

    /** When the last check finished (epoch millis), or 0. */
    public static long checkedAt() {
        return checkedAt;
    }

    /** Updates not ignored by the user. */
    public static int updateCount() {
        int n = 0;
        for (ModStatus m : mods) if (m.wantsUpdate()) n++;
        return n;
    }

    /** Starts a check in the background (does nothing if one is running). */
    public static synchronized void checkAsync() {
        if (running) return;
        running = true;
        summary = "Checking...";
        final Thread t = new Thread(UpdateChecker::run, "ModUpdateChecker");
        t.setDaemon(true);
        t.start();
    }

    /** Checks on this thread, returning when done. */
    public static void checkNow() {
        synchronized (UpdateChecker.class) {
            if (running) return;
            running = true;
        }
        run();
    }

    private static void run() {
        try {
            check();
        } catch (Throwable e) {
            Env.log.warn("Update check failed: {}", e.toString());
            summary = "Check failed: " + e.getMessage();
        } finally {
            running = false;
            checkedAt = System.currentTimeMillis();
        }
    }

    private static void check() throws Exception {
        final List<ModStatus> found = new ArrayList<>(Env.scanner.get());
        found.removeIf(m -> Config.get().skippedMods.contains(m.id));
        found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        mods = found;
        summary = "Checking " + found.size() + " mods on Modrinth...";

        final Map<String, ModStatus> byHash = new LinkedHashMap<>();
        for (ModStatus m : found) {
            try {
                byHash.put(hash(m.jar, "SHA-1"), m);
            } catch (Exception e) {
                m.state = ModStatus.State.ERROR;
                m.error = "Couldn't read the jar: " + e.getMessage();
            }
        }
        try {
            modrinth(byHash);
        } catch (Exception e) {
            Env.log.warn("Modrinth lookup failed: {}", e.toString());
        }

        final List<ModStatus> rest = new ArrayList<>();
        for (ModStatus m : found) {
            if (m.state == ModStatus.State.CHECKING) rest.add(m);
        }
        summary = "Checking " + rest.size() + " mods on GitHub...";
        final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "ModUpdateChecker-GitHub");
            t.setDaemon(true);
            return t;
        });
        final Map<String, CacheEntry> cache = loadCache();
        for (ModStatus m : rest) {
            pool.execute(() -> {
                try {
                    github(m, cache);
                } catch (Exception e) {
                    m.state = ModStatus.State.ERROR;
                    m.error = e.getMessage();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);
        saveCache(cache);

        final int updates = updateCount();
        int notFound = 0;
        for (ModStatus m : found) {
            if (m.state == ModStatus.State.CHECKING) {
                m.state = ModStatus.State.ERROR;
                m.error = "Timed out";
            }
            if (m.state == ModStatus.State.NOT_FOUND) notFound++;
            if (Env.verbose) Env.log.info("  {} {}: {}{}{}", m.name, m.version, m.state,
                    m.latestVersion != null && m.state == ModStatus.State.UPDATE_AVAILABLE ? " -> " + m.latestVersion : "",
                    m.error != null ? " (" + m.error + ")" : "");
        }
        summary = found.size() + " mods checked · " + (updates == 0 ? "everything is up to date" : updates + (updates == 1 ? " update" : " updates"))
                + (notFound > 0 ? " · " + notFound + " not found" : "");
        if (Env.verbose) Env.log.info("Update check done: {}", summary);
    }

    // ---- installed mods ----

    /**
     * The GitHub repository ("owner/repo") for a mod: from {@link Config#githubOverrides}, the
     * {@code custom.modupdatechecker.github} value in its fabric.mod.json, or a github.com link in
     * its contact info ({@code sources}, {@code homepage} or {@code issues}). Null if none.
     */
    public static String githubRepo(String id, String customGithub, Map<String, String> contact) {
        final String override = Config.get().githubOverrides.get(id);
        if (override != null && override.contains("/")) return override;
        if (customGithub != null && customGithub.contains("/")) return customGithub;
        for (String key : new String[] {"sources", "homepage", "issues"}) {
            final String url = contact.get(key);
            if (url == null) continue;
            final Matcher m = GITHUB_URL.matcher(url);
            if (m.find()) {
                String repo = m.group(2);
                if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
                return m.group(1) + "/" + repo;
            }
        }
        return null;
    }

    // ---- Modrinth ----

    private static void modrinth(Map<String, ModStatus> byHash) throws Exception {
        if (byHash.isEmpty()) return;
        final Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("hashes", new ArrayList<>(byHash.keySet()));
        lookup.put("algorithm", "sha1");
        final Map<String, Object> current = Json.obj(Json.parse(post(MODRINTH + "/version_files", lookup)));
        if (current.isEmpty()) return;

        // Only look for updates on the loader the installed version was made for: Ornithe can't run
        // Legacy Fabric mods (and the other way round), so a mod that moved loaders mustn't be
        // "updated" onto the wrong one. One request per loader group (usually just one).
        final Map<List<String>, List<String>> byLoaders = new LinkedHashMap<>();
        for (String hash : current.keySet()) {
            final List<String> loaders = loadersFor(Json.arr(Json.obj(current, hash), "loaders"));
            if (!loaders.isEmpty()) byLoaders.computeIfAbsent(loaders, k -> new ArrayList<>()).add(hash);
        }
        final Map<String, Object> latest = new LinkedHashMap<>();
        for (Map.Entry<List<String>, List<String>> group : byLoaders.entrySet()) {
            final Map<String, Object> query = new LinkedHashMap<>();
            query.put("hashes", group.getValue());
            query.put("algorithm", "sha1");
            query.put("loaders", group.getKey());
            query.put("game_versions", List.of(Env.minecraftVersion));
            latest.putAll(Json.obj(Json.parse(post(MODRINTH + "/version_files/update", query))));
        }

        for (String hash : current.keySet()) {
            final ModStatus m = byHash.get(hash);
            if (m == null) continue;
            final Map<String, Object> cur = Json.obj(current, hash);
            m.source = ModStatus.Source.MODRINTH;
            final String project = Json.str(cur, "project_id");
            m.pageUrl = "https://modrinth.com/mod/" + project;
            final Map<String, Object> next = Json.obj(latest, hash);
            if (next.isEmpty()) {
                // No version for this loader and game version besides (maybe) the installed one.
                m.latestVersion = Json.str(cur, "version_number");
                m.state = ModStatus.State.UP_TO_DATE;
                continue;
            }
            m.latestVersion = Json.str(next, "version_number");
            if (Json.str(next, "id").equals(Json.str(cur, "id"))) {
                m.state = ModStatus.State.UP_TO_DATE;
                continue;
            }
            final Instant curDate = Instant.parse(Json.str(cur, "date_published"));
            final Instant nextDate = Instant.parse(Json.str(next, "date_published"));
            if (!nextDate.isAfter(curDate)) {
                m.state = ModStatus.State.NEWER;
                continue;
            }
            m.pageUrl = "https://modrinth.com/mod/" + project + "/version/" + Json.str(next, "id");
            m.changelog = trim(Json.str(next, "changelog"));
            final Map<String, Object> file = primaryFile(Json.arr(next, "files"));
            if (file != null) {
                m.downloadUrl = Json.str(file, "url");
                m.downloadName = Json.str(file, "filename");
                final String sha1 = Json.str(Json.obj(file, "hashes"), "sha1");
                if (!sha1.isEmpty()) m.downloadHash = "sha1:" + sha1;
            }
            m.state = ModStatus.State.UPDATE_AVAILABLE;
        }
    }

    /**
     * The loaders to look for updates on, from the installed version's: just Ornithe for an
     * Ornithe build (even one also tagged Fabric), otherwise whichever of Fabric and Legacy Fabric
     * it was tagged with. Empty for anything else (a Forge jar, say), which isn't checked.
     */
    static List<String> loadersFor(List<Object> installed) {
        final List<String> tags = new ArrayList<>();
        for (Object o : installed) if (o instanceof String s) tags.add(s.toLowerCase(Locale.ROOT));
        if (tags.contains("ornithe")) return List.of("ornithe");
        final List<String> out = new ArrayList<>();
        for (String l : List.of("fabric", "legacy-fabric")) if (tags.contains(l)) out.add(l);
        return out;
    }

    private static Map<String, Object> primaryFile(List<Object> files) {
        if (files.isEmpty()) return null;
        for (Object f : files) {
            final Map<String, Object> o = Json.obj(f);
            if (Json.bool(o, "primary", false)) return o;
        }
        return Json.obj(files.get(0));
    }

    // ---- GitHub ----

    private static final class CacheEntry {
        long time;
        int status;
        String body;
    }

    private static void github(ModStatus m, Map<String, CacheEntry> cache) throws Exception {
        if (m.githubRepo == null) {
            m.state = ModStatus.State.NOT_FOUND;
            return;
        }
        m.source = ModStatus.Source.GITHUB;
        m.pageUrl = "https://github.com/" + m.githubRepo + "/releases";
        CacheEntry entry;
        synchronized (cache) {
            entry = cache.get(m.githubRepo);
        }
        final long maxAge = TimeUnit.MINUTES.toMillis(Math.max(0, Config.get().githubCacheMinutes));
        if (entry == null || System.currentTimeMillis() - entry.time > maxAge) {
            final HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + m.githubRepo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/vnd.github+json");
            final String token = Config.get().githubToken;
            if (token != null && !token.trim().isEmpty()) req.header("Authorization", "Bearer " + token.trim());
            final HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 403 || res.statusCode() == 429) {
                throw new Exception("GitHub's rate limit was hit; try again later or set githubToken in the config");
            }
            entry = new CacheEntry();
            entry.time = System.currentTimeMillis();
            entry.status = res.statusCode();
            entry.body = res.statusCode() == 200 ? res.body() : null;
            synchronized (cache) {
                cache.put(m.githubRepo, entry);
            }
        }
        if (entry.status == 404) {
            m.state = ModStatus.State.NOT_FOUND;
            m.error = "No releases on " + m.githubRepo;
            return;
        }
        if (entry.status != 200 || entry.body == null) {
            throw new Exception("GitHub answered " + entry.status);
        }
        final Map<String, Object> release = Json.obj(Json.parse(entry.body));
        final String tag = Json.str(release, "tag_name");
        m.latestVersion = tag;
        m.changelog = trim(Json.str(release, "body"));
        final String releasePage = Json.str(release, "html_url");

        final int cmp = Versions.compare(m.version, tag);
        if (cmp >= 0) {
            m.state = cmp == 0 ? ModStatus.State.UP_TO_DATE : ModStatus.State.NEWER;
            return;
        }
        if (!releasePage.isEmpty()) m.pageUrl = releasePage;
        final Map<String, Object> asset = pickAsset(Json.arr(release, "assets"), m.jar.getFileName().toString());
        if (asset != null) {
            m.downloadUrl = Json.str(asset, "browser_download_url");
            m.downloadName = Json.str(asset, "name");
            final String digest = Json.str(asset, "digest");
            if (digest.startsWith("sha256:")) m.downloadHash = digest;
        }
        m.state = ModStatus.State.UPDATE_AVAILABLE;
    }

    /** The release jar most like the installed one, or null if it's unclear which to take. */
    private static Map<String, Object> pickAsset(List<Object> assets, String installedName) {
        final String installed = installedName.toLowerCase(Locale.ROOT);
        final String mc = Env.minecraftVersion;
        Map<String, Object> best = null;
        int bestScore = Integer.MIN_VALUE;
        int jars = 0;
        boolean bestHasMc = false;
        for (Object e : assets) {
            final Map<String, Object> a = Json.obj(e);
            final String name = Json.str(a, "name").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar") || name.contains("-sources") || name.contains("-dev") || name.contains("-javadoc")) continue;
            // Never offer a jar for another loader (repos often still carry old Forge releases).
            if (name.contains("forge") || name.contains("quilt")) continue;
            final boolean hasMc = name.contains(mc);
            jars++;
            int score = 0;
            if (hasMc) score += 4;
            if (installed.contains("ornithe") == name.contains("ornithe")) score += 2;
            if (installed.contains("fabric") == name.contains("fabric")) score += 1;
            final String prefix = installed.length() >= 4 ? installed.substring(0, 4) : installed;
            if (name.startsWith(prefix)) score += 2;
            if (score > bestScore) {
                best = a;
                bestScore = score;
                bestHasMc = hasMc;
            }
        }
        if (jars == 1) return best;
        return bestHasMc ? best : null;
    }

    private static Path cacheFile() {
        return Config.dir().resolve("github-cache.json");
    }

    private static Map<String, CacheEntry> loadCache() {
        final Map<String, CacheEntry> cache = new HashMap<>();
        try {
            for (Map.Entry<String, Object> e : Json.obj(Json.parse(Files.readString(cacheFile(), StandardCharsets.UTF_8))).entrySet()) {
                final Map<String, Object> o = Json.obj(e.getValue());
                final CacheEntry c = new CacheEntry();
                c.time = Json.num(o, "time", 0);
                c.status = (int) Json.num(o, "status", 0);
                c.body = o.get("body") instanceof String s ? s : null;
                cache.put(e.getKey(), c);
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    private static void saveCache(Map<String, CacheEntry> cache) {
        final Map<String, Object> out = new LinkedHashMap<>();
        synchronized (cache) {
            for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
                final Map<String, Object> o = new LinkedHashMap<>();
                o.put("time", e.getValue().time);
                o.put("status", e.getValue().status);
                o.put("body", e.getValue().body);
                out.put(e.getKey(), o);
            }
        }
        try {
            Files.createDirectories(cacheFile().getParent());
            Files.writeString(cacheFile(), Json.write(out, false), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Env.log.warn("Couldn't save the GitHub cache: {}", e.toString());
        }
    }

    /** Forgets cached GitHub answers, so the next check asks again. */
    public static void clearCache() {
        try {
            Files.deleteIfExists(cacheFile());
        } catch (Exception ignored) {
        }
    }

    // ---- helpers ----

    private static String post(String url, Object body) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body, false)))
                .build();
        final HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new Exception("Modrinth answered " + res.statusCode() + " for " + url);
        return res.body();
    }

    private static String trim(String text) {
        if (text == null) return null;
        final String t = text.replace("\r", "").trim();
        if (t.isEmpty()) return null;
        return t.length() > 2000 ? t.substring(0, 2000) + "..." : t;
    }

    public static String hash(Path file, String algorithm) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
