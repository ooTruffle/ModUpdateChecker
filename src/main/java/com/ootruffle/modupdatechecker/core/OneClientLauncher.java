package com.ootruffle.modupdatechecker.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OneClient support for the pre-launch check: finds out whether the game was started by OneClient,
 * and adds the check to OneClient's pre-launch command.
 * <p>
 * Where things are: OneClient's data folder holds {@code clusters/<name>} (each with its own
 * {@code mods}), and most clusters run in one shared game folder, {@code <data>/.minecraft} (or
 * {@code <data>/shared/<group>}), with Fabric pointed at the cluster's mods
 * ({@code -Dfabric.modsFolder}). A cluster with a {@code .dedicated_directory} marker runs in its
 * own folder instead.
 * <p>
 * OneClient runs the pre-launch command with {@code cmd /C} (or {@code sh -c}) in the game folder
 * and waits for it. It has no {@code $INST_...} variables, and quotes inside the command don't
 * survive how it's passed to cmd, so the command is a short relative one with no quotes: it runs
 * {@code config/modupdatechecker/prelaunch.cmd} (or {@code .sh}) if the game folder has one. That
 * script, written by the mod each time the game starts, holds the Java path and the jar. Game
 * folders without this mod have no script, so the command does nothing there. In a shared folder
 * the check works out which cluster is starting (see {@link #launchingModsDir}) and does nothing
 * for clusters without this mod.
 * <p>
 * The command goes in the global game settings ({@code global_game_settings.hook_pre} in
 * {@code settings.json}), which every cluster uses unless it sets its own. OneClient keeps its
 * settings in memory and saves them from there, so the file is only edited while OneClient is
 * closed (see {@link com.ootruffle.modupdatechecker.install.LauncherSetup}). A failed pre-launch
 * command doesn't stop OneClient's launch, so the window has no Quit button there.
 */
public final class OneClientLauncher {

    public static final String COMMAND_WINDOWS = "if exist config\\modupdatechecker\\prelaunch.cmd config\\modupdatechecker\\prelaunch.cmd";
    public static final String COMMAND_UNIX = "if [ -f config/modupdatechecker/prelaunch.sh ]; then sh config/modupdatechecker/prelaunch.sh; fi";

    private static final Pattern HOOK_PRE = Pattern.compile("\"hook_pre\"\\s*:\\s*(null|\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern GLOBAL = Pattern.compile("\"global_game_settings\"\\s*:\\s*\\{");

    public enum State {
        /** Not a OneClient cluster. */
        NOT_ONECLIENT,
        /** The pre-launch check is already set. */
        CONFIGURED,
        /** Another pre-launch command is set; it's never replaced. */
        OTHER_COMMAND,
        /** Can be set up. */
        READY,
    }

    /** OneClient's process and program are null when it isn't running (it can then be set up straight away). */
    public record Info(State state, Path settingsJson, ProcessHandle oneClient, Path oneClientExe) {}

    private OneClientLauncher() {}

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String command() {
        return windows() ? COMMAND_WINDOWS : COMMAND_UNIX;
    }

    /**
     * OneClient's data folder, from a cluster's mods folder ({@code <data>/clusters/<name>/mods})
     * or a game folder ({@code <data>/.minecraft}, {@code <data>/shared/<group>} or a cluster's own
     * folder). Null when neither is OneClient's.
     */
    public static Path dataDir(Path modsDir, Path gameDir) {
        if (modsDir != null) {
            final Path cluster = modsDir.toAbsolutePath().normalize().getParent();
            final Path data = cluster == null ? null : parentIfNamed(cluster, "clusters");
            if (isData(data)) return data;
        }
        if (gameDir != null) {
            final Path game = gameDir.toAbsolutePath().normalize();
            final boolean shared = game.getFileName() != null && game.getFileName().toString().equals(".minecraft");
            for (Path data : new Path[] {shared ? game.getParent() : null, parentIfNamed(game, "shared"), parentIfNamed(game, "clusters")}) {
                if (isData(data)) return data;
            }
        }
        return null;
    }

    /** The grandparent of [dir] if its parent is called [name], else null. */
    private static Path parentIfNamed(Path dir, String name) {
        final Path parent = dir.getParent();
        if (parent == null || parent.getFileName() == null || !parent.getFileName().toString().equalsIgnoreCase(name)) return null;
        return parent.getParent();
    }

    private static boolean isData(Path data) {
        return data != null && Files.isDirectory(data.resolve("clusters")) && Files.isDirectory(data.resolve("metadata"))
                && Files.isRegularFile(data.resolve("settings.json"));
    }

    /**
     * The mods folder of the cluster OneClient is starting in [gameDir] (the pre-launch command's
     * folder), or null if that can't be told. A cluster's own folder has its mods in it. In a
     * shared folder, OneClient writes {@code .oneclient-global.json} with the cluster's id before
     * running the command, and each cluster folder has a {@code .oneclient-mods.json} with its id.
     */
    public static Path launchingModsDir(Path gameDir) {
        final Path game = gameDir.toAbsolutePath().normalize();
        final Path data = dataDir(null, game);
        if (data == null) return null;
        if (parentIfNamed(game, "clusters") != null) return game.resolve("mods");
        final Long id = clusterId(game.resolve(".oneclient-global.json"));
        Path newest = null;
        long newestTime = 0;
        try (java.nio.file.DirectoryStream<Path> clusters = Files.newDirectoryStream(data.resolve("clusters"))) {
            for (Path cluster : clusters) {
                final Path manifest = cluster.resolve(".oneclient-mods.json");
                if (!Files.isRegularFile(manifest)) continue;
                if (id != null && id.equals(clusterId(manifest))) return cluster.resolve("mods");
                final long t = Files.getLastModifiedTime(manifest).toMillis();
                if (t > newestTime) {
                    newestTime = t;
                    newest = cluster;
                }
            }
        } catch (Exception e) {
            return null;
        }
        // No global manifest (nothing shared to put there): OneClient rewrote the starting
        // cluster's own manifest moments ago, while getting the launch ready.
        if (id == null && newest != null && System.currentTimeMillis() - newestTime < 120_000) return newest.resolve("mods");
        return null;
    }

    private static Long clusterId(Path manifest) {
        try {
            if (!Files.isRegularFile(manifest)) return null;
            final Object v = Json.obj(Json.parse(Files.readString(manifest, StandardCharsets.UTF_8))).get("cluster_id");
            return v instanceof Double d ? d.longValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Looks at the game's surroundings (call from the game). */
    public static Info detect() {
        final Path data = dataDir(Env.modsDir(), Env.gameDir);
        if (data == null) return none();
        final Path settings = data.resolve("settings.json");
        if (!Files.isRegularFile(settings)) return none();
        // OneClient starts the game itself, so it's the game's parent (or close to it).
        ProcessHandle oneClient = null;
        Path exe = null;
        Optional<ProcessHandle> p = ProcessHandle.current().parent();
        for (int depth = 0; depth < 3 && p.isPresent(); depth++) {
            final Optional<String> cmd = p.get().info().command();
            if (cmd.isPresent() && isOneClient(Path.of(cmd.get()))) {
                oneClient = p.get();
                exe = Path.of(cmd.get());
                break;
            }
            p = p.get().parent();
        }
        if (oneClient == null) {
            // Started some other way (or OneClient closed after launching): any OneClient running?
            oneClient = ProcessHandle.allProcesses()
                    .filter(h -> h.info().command().map(c -> isOneClient(Path.of(c))).orElse(false))
                    .findFirst().orElse(null);
            if (oneClient != null) exe = Path.of(oneClient.info().command().get());
        }
        return new Info(state(settings), settings, oneClient, exe);
    }

    private static boolean isOneClient(Path program) {
        final String name = program.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("oneclient");
    }

    private static Info none() {
        return new Info(State.NOT_ONECLIENT, null, null, null);
    }

    /** Whether the check is set, something else is, or it can be set. */
    public static State state(Path settingsJson) {
        try {
            final Map<String, Object> o = Json.obj(Json.parse(Files.readString(settingsJson, StandardCharsets.UTF_8)));
            final String pre = Json.str(Json.obj(o, "global_game_settings"), "hook_pre");
            if (isOurs(pre)) return State.CONFIGURED;
            return pre.isBlank() ? State.READY : State.OTHER_COMMAND;
        } catch (Exception e) {
            return State.NOT_ONECLIENT;
        }
    }

    static boolean isOurs(String command) {
        return command.contains("modupdatechecker") && (command.contains("prelaunch") || command.contains("PrelaunchCheck"));
    }

    /**
     * Sets the global pre-launch command in settings.json (OneClient must be closed), keeping a
     * backup. Only that value is changed; the rest of the file is left as it was.
     */
    public static String patch(Path settingsJson) throws Exception {
        final State state = state(settingsJson);
        if (state == State.CONFIGURED) return "already set";
        if (state != State.READY) return "not changed: " + (state == State.OTHER_COMMAND ? "another pre-launch command is set" : "settings.json couldn't be read");
        final String text = Files.readString(settingsJson, StandardCharsets.UTF_8);
        final String value = "\"hook_pre\": " + Json.write(command(), false);
        final String patched;
        final Matcher m = HOOK_PRE.matcher(text);
        if (m.find()) {
            patched = text.substring(0, m.start()) + value + text.substring(m.end());
        } else {
            final Matcher g = GLOBAL.matcher(text);
            if (!g.find()) return "not changed: settings.json has no global game settings";
            patched = text.substring(0, g.end()) + "\n    " + value + "," + text.substring(g.end());
        }
        // Make sure the result reads back as intended before replacing the file.
        final Map<String, Object> check = Json.obj(Json.parse(patched));
        if (!command().equals(Json.str(Json.obj(check, "global_game_settings"), "hook_pre"))) {
            return "not changed: couldn't edit settings.json safely";
        }
        Files.copy(settingsJson, settingsJson.resolveSibling("settings.json.modupdatechecker-backup"), StandardCopyOption.REPLACE_EXISTING);
        final Path tmp = settingsJson.resolveSibling("settings.json.modupdatechecker-tmp");
        Files.writeString(tmp, patched, StandardCharsets.UTF_8);
        Files.move(tmp, settingsJson, StandardCopyOption.REPLACE_EXISTING);
        return "pre-launch check added";
    }

    /**
     * Writes {@code config/modupdatechecker/prelaunch.cmd} (Windows) or {@code prelaunch.sh}, which
     * the pre-launch command runs: the Java the game is running on, with {@code prelaunch.jar}. Done
     * each time the game starts, so a new Java version or a moved folder is picked up.
     */
    public static void writeScript(Path dataDir, Path java) throws Exception {
        Files.createDirectories(dataDir);
        final String main = "com.ootruffle.modupdatechecker.prelaunch.PrelaunchCheck --launcher oneclient";
        final Path file;
        final String script;
        if (windows()) {
            file = dataDir.resolve("prelaunch.cmd");
            // UTF-8 code page first, so a Java path with non-ASCII characters is read correctly.
            script = "@echo off\r\n"
                    + "chcp 65001 >nul\r\n"
                    + "\"" + java + "\" -cp \"%~dp0prelaunch.jar\" " + main + "\r\n"
                    + "exit /b %ERRORLEVEL%\r\n";
        } else {
            file = dataDir.resolve("prelaunch.sh");
            script = "#!/bin/sh\n"
                    + "exec '" + java.toString().replace("'", "'\\''") + "' -cp \"$(dirname \"$0\")/prelaunch.jar\" " + main + "\n";
        }
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(script)) return;
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, script, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
