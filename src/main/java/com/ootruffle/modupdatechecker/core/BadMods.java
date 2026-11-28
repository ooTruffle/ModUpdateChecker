package com.ootruffle.modupdatechecker.core;

import com.ootruffle.modupdatechecker.UpdateChecker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The list of mods known to be bad (malware, broken, or banned on servers), kept as
 * {@code bad-mods.json} in this mod's repository so it can change without a release. It's fetched
 * at most every {@link #REFRESH_MS}, and the last copy is kept in the config folder for when the
 * game starts offline. Only uses the JDK, so the pre-launch check reads it too.
 *
 * <pre>{
 *   "mods": [
 *     {
 *       "name": "Shown name",
 *       "id": "modid",                  // fabric.mod.json / mcmod.info id; optional
 *       "versions": ["1.0.0", "1.0.1"], // only these versions of the id; optional (all versions)
 *       "sha1": ["..."],                // exact files; optional
 *       "files": ["(?i)^evil.*\\.jar$"],// file name patterns; optional
 *       "reason": "Steals session tokens",
 *       "url": "https://..."            // more about it; optional
 *     }
 *   ]
 * }</pre>
 */
public final class BadMods {

    public static final String URL = "https://raw.githubusercontent.com/ooTruffle/ModUpdateChecker/main/bad-mods.json";
    private static final long REFRESH_MS = 30 * 60 * 1000L;
    /** How long to wait before trying again after the fetch failed (offline). */
    private static final long RETRY_MS = 2 * 60 * 1000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** One entry of the list. */
    public static final class Entry {
        public final String name;
        final String id;
        final List<String> versions;
        final List<String> sha1;
        final List<Pattern> files;
        public final String reason;
        /** More about it, or null. */
        public final String url;

        Entry(String name, String id, List<String> versions, List<String> sha1, List<Pattern> files, String reason, String url) {
            this.name = name;
            this.id = id;
            this.versions = versions;
            this.sha1 = sha1;
            this.files = files;
            this.reason = reason;
            this.url = url;
        }

        boolean matches(String jarId, String jarVersion, String jarSha1, String fileName) {
            if (jarSha1 != null && sha1.contains(jarSha1.toLowerCase(Locale.ROOT))) return true;
            if (id != null && id.equals(jarId) && (versions.isEmpty() || versions.contains(jarVersion))) return true;
            for (Pattern p : files) if (p.matcher(fileName).find()) return true;
            return false;
        }
    }

    private static volatile List<Entry> entries;
    private static volatile long fetchedAt;

    private BadMods() {}

    /** The entry in [list] that a jar matches, or null. */
    public static Entry match(List<Entry> list, String id, String version, String sha1, String fileName) {
        for (Entry e : list) if (e.matches(id, version, sha1, fileName)) return e;
        return null;
    }

    /** The list, fetched if it's stale; the saved copy (or nothing) when it can't be. */
    public static synchronized List<Entry> list() {
        if (entries != null && System.currentTimeMillis() - fetchedAt < REFRESH_MS) return entries;
        final Path cache = Env.dataDir().resolve("bad-mods.json");
        try {
            final HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UpdateChecker.USER_AGENT)
                    .build();
            final HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            final List<Entry> parsed = parse(res.body());
            entries = parsed;
            fetchedAt = System.currentTimeMillis();
            try {
                Files.createDirectories(cache.getParent());
                final Path tmp = cache.resolveSibling("bad-mods.json.tmp");
                Files.writeString(tmp, res.body(), StandardCharsets.UTF_8);
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Env.log.warn("Couldn't save the bad mods list: {}", e.toString());
            }
            return parsed;
        } catch (Exception e) {
            Env.log.warn("Couldn't fetch the bad mods list ({}); using the saved copy", e.toString());
        }
        if (entries == null) {
            try {
                entries = Files.isRegularFile(cache) ? parse(Files.readString(cache, StandardCharsets.UTF_8)) : List.of();
            } catch (Exception e) {
                Env.log.warn("Couldn't read the saved bad mods list: {}", e.toString());
                entries = List.of();
            }
        }
        // Try again soon rather than waiting out the refresh time.
        fetchedAt = System.currentTimeMillis() - REFRESH_MS + RETRY_MS;
        return entries;
    }

    static List<Entry> parse(String text) {
        final List<Entry> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(text)), "mods")) {
            final Map<String, Object> m = Json.obj(o);
            final String id = blankToNull(Json.str(m, "id"));
            final List<String> sha1 = new ArrayList<>();
            for (Object h : Json.arr(m, "sha1")) if (h instanceof String s && !s.isBlank()) sha1.add(s.trim().toLowerCase(Locale.ROOT));
            final List<Pattern> files = new ArrayList<>();
            for (Object f : Json.arr(m, "files")) {
                if (!(f instanceof String s) || s.isBlank()) continue;
                try {
                    files.add(Pattern.compile(s));
                } catch (Exception e) {
                    Env.log.warn("Bad pattern in the bad mods list: {}", s);
                }
            }
            if (id == null && sha1.isEmpty() && files.isEmpty()) continue; // Matches nothing.
            final List<String> versions = new ArrayList<>();
            for (Object v : Json.arr(m, "versions")) if (v instanceof String s && !s.isBlank()) versions.add(s);
            final String name = blankToNull(Json.str(m, "name"));
            final String reason = blankToNull(Json.str(m, "reason"));
            out.add(new Entry(name != null ? name : id != null ? id : "A known bad mod", id,
                    Collections.unmodifiableList(versions), Collections.unmodifiableList(sha1), Collections.unmodifiableList(files),
                    reason != null ? reason : "It's on the bad mods list", blankToNull(Json.str(m, "url"))));
        }
        return Collections.unmodifiableList(out);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
