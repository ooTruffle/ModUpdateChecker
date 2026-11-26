package com.ootruffle.modupdatechecker;

import com.ootruffle.modupdatechecker.core.Env;
import com.ootruffle.modupdatechecker.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code config/modupdatechecker.json}. */
public final class Config {

    /** Check for updates as the game starts. */
    public boolean checkOnStartup = true;

    /** Look for duplicate mods, mods for other loaders and mods this one replaces. */
    public boolean checkModProblems = true;

    /** Show the "Mod Updates" button on the title screen and pause menu. */
    public boolean showMenuButton = true;

    /**
     * Optional GitHub token, only needed if you hit GitHub's limit of 60 unauthenticated requests
     * an hour. A token with no scopes is enough for public repositories.
     */
    public String githubToken = "";

    /** How long a GitHub answer is reused before asking again, in minutes. */
    public int githubCacheMinutes = 360;

    /** Mod id → the version you chose to skip. A newer version than that is shown again. */
    public Map<String, String> ignoredVersions = new LinkedHashMap<>();

    /** Mod id → "owner/repo", for mods whose fabric.mod.json doesn't link their GitHub repository. */
    public Map<String, String> githubOverrides = new LinkedHashMap<>();

    /** Mod ids never checked at all. */
    public List<String> skippedMods = new ArrayList<>();

    /** "Not now" on the in-game offer to set up the pre-launch check in Prism. */
    public boolean prismPromptDismissed = false;

    /** "Don't show again" on the notice that OneClient updates mods itself. */
    public boolean oneClientNoticeDismissed = false;

    private static Config instance = new Config();

    public static Config get() {
        return instance;
    }

    /** {@code config/modupdatechecker}. */
    public static Path dir() {
        return Env.dataDir();
    }

    private static Path file() {
        return Env.configDir().resolve(Env.MOD_ID + ".json");
    }

    public static synchronized void load() {
        final Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                final Map<String, Object> o = Json.obj(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
                final Config c = new Config();
                c.checkOnStartup = Json.bool(o, "checkOnStartup", c.checkOnStartup);
                c.showMenuButton = Json.bool(o, "showMenuButton", c.showMenuButton);
                c.checkModProblems = Json.bool(o, "checkModProblems", c.checkModProblems);
                c.githubToken = o.get("githubToken") instanceof String s ? s : "";
                c.githubCacheMinutes = (int) Json.num(o, "githubCacheMinutes", c.githubCacheMinutes);
                for (Map.Entry<String, Object> e : Json.obj(o, "ignoredVersions").entrySet()) {
                    if (e.getValue() instanceof String s) c.ignoredVersions.put(e.getKey(), s);
                }
                for (Map.Entry<String, Object> e : Json.obj(o, "githubOverrides").entrySet()) {
                    if (e.getValue() instanceof String s) c.githubOverrides.put(e.getKey(), s);
                }
                c.prismPromptDismissed = Json.bool(o, "prismPromptDismissed", false);
                c.oneClientNoticeDismissed = Json.bool(o, "oneClientNoticeDismissed", false);
                for (Object id : Json.arr(o, "skippedMods")) {
                    if (id instanceof String s) c.skippedMods.add(s);
                }
                instance = c;
            } catch (Exception e) {
                Env.log.warn("Couldn't read {}, using defaults: {}", file, e.toString());
            }
        }
        save();
    }

    public static synchronized void save() {
        final Config c = instance;
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("checkOnStartup", c.checkOnStartup);
        o.put("showMenuButton", c.showMenuButton);
        o.put("checkModProblems", c.checkModProblems);
        o.put("githubToken", c.githubToken);
        o.put("githubCacheMinutes", c.githubCacheMinutes);
        o.put("ignoredVersions", c.ignoredVersions);
        o.put("githubOverrides", c.githubOverrides);
        o.put("skippedMods", c.skippedMods);
        o.put("prismPromptDismissed", c.prismPromptDismissed);
        o.put("oneClientNoticeDismissed", c.oneClientNoticeDismissed);
        final Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(o, true), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Env.log.warn("Couldn't save {}: {}", file, e.toString());
        }
    }
}
