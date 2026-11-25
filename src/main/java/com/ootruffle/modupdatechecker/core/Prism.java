package com.ootruffle.modupdatechecker.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prism Launcher support: finds out whether the game was started by Prism, and adds the pre-launch
 * update check to the instance's settings ({@code instance.cfg}).
 * <p>
 * Prism keeps its settings in memory and rewrites {@code instance.cfg} from them (after every game
 * session, to save the play time), so the file is only edited while Prism is closed (see
 * {@link com.ootruffle.modupdatechecker.install.PrismSetup}).
 */
public final class Prism {

    /** What the pre-launch command line in instance.cfg should be (Prism's ini quoting). */
    public static final String COMMAND = "\\\"$INST_JAVA\\\" -cp \\\"$INST_MC_DIR/config/modupdatechecker/prelaunch.jar\\\" "
            + "com.ootruffle.modupdatechecker.prelaunch.PrelaunchCheck --gui";

    public enum State {
        /** Not started by Prism (or the instance folder doesn't look like Prism's). */
        NOT_PRISM,
        /** The pre-launch check is already set. */
        CONFIGURED,
        /** Another pre-launch command is set; it's never replaced. */
        OTHER_COMMAND,
        /** Can be set up. */
        READY,
    }

    public record Info(State state, Path instanceDir, Path instanceCfg, Path globalCfg, ProcessHandle prism, Path prismExe) {}

    private Prism() {}

    /** Looks at the game's surroundings (call from the game). */
    public static Info detect() {
        final Path instanceDir = Env.gameDir.getParent();
        if (instanceDir == null) return none();
        final Path cfg = instanceDir.resolve("instance.cfg");
        if (!Files.isRegularFile(cfg) || !Files.isRegularFile(instanceDir.resolve("mmc-pack.json"))) return none();
        // Prism launches the game itself, so it's the game's parent (or close to it).
        ProcessHandle prism = null;
        Path exe = null;
        Optional<ProcessHandle> p = ProcessHandle.current().parent();
        for (int depth = 0; depth < 3 && p.isPresent(); depth++) {
            final Optional<String> cmd = p.get().info().command();
            if (cmd.isPresent() && Path.of(cmd.get()).getFileName().toString().toLowerCase(Locale.ROOT).startsWith("prismlauncher")) {
                prism = p.get();
                exe = Path.of(cmd.get());
                break;
            }
            p = p.get().parent();
        }
        if (prism == null) return none();
        // Prism's data folder holds prismlauncher.cfg and (by default) instances/<this instance>.
        Path global = null;
        final Path instances = instanceDir.getParent();
        if (instances != null && instances.getParent() != null
                && Files.isRegularFile(instances.getParent().resolve("prismlauncher.cfg"))) {
            global = instances.getParent().resolve("prismlauncher.cfg");
        }
        return new Info(state(cfg, global), instanceDir, cfg, global, prism, exe);
    }

    private static Info none() {
        return new Info(State.NOT_PRISM, null, null, null, null, null);
    }

    /** Whether the check is set, something else is, or it can be set. */
    public static State state(Path instanceCfg, Path globalCfg) {
        try {
            final List<String> lines = Files.readAllLines(instanceCfg, StandardCharsets.UTF_8);
            final String pre = value(lines, "PreLaunchCommand");
            if (pre != null && pre.contains("PrelaunchCheck")) return State.CONFIGURED;
            final boolean override = "true".equalsIgnoreCase(value(lines, "OverrideCommands"));
            if (override) {
                return pre == null || pre.isBlank() ? State.READY : State.OTHER_COMMAND;
            }
            // Not overridden: Prism uses the global commands. A global pre-launch command would be lost.
            if (globalCfg != null && Files.isRegularFile(globalCfg)) {
                final String globalPre = value(Files.readAllLines(globalCfg, StandardCharsets.UTF_8), "PreLaunchCommand");
                if (globalPre != null && !globalPre.isBlank()) {
                    return globalPre.contains("PrelaunchCheck") ? State.CONFIGURED : State.OTHER_COMMAND;
                }
            }
            return State.READY;
        } catch (Exception e) {
            return State.NOT_PRISM;
        }
    }

    /**
     * Adds the pre-launch check to instance.cfg (Prism must be closed), keeping a backup. If the
     * instance used the global commands, the global wrapper and post-exit commands are copied in, since
     * overriding them for this instance would otherwise drop them. Returns what happened.
     */
    public static String patch(Path instanceCfg, Path globalCfg) throws Exception {
        final State state = state(instanceCfg, globalCfg);
        if (state == State.CONFIGURED) return "already set";
        if (state != State.READY) return "not changed: another pre-launch command is set (" + state + ")";
        final List<String> lines = new ArrayList<>(Files.readAllLines(instanceCfg, StandardCharsets.UTF_8));
        final boolean wasOverridden = "true".equalsIgnoreCase(value(lines, "OverrideCommands"));
        final List<String[]> set = new ArrayList<>();
        set.add(new String[] {"OverrideCommands", "true"});
        set.add(new String[] {"PreLaunchCommand", COMMAND});
        if (!wasOverridden && globalCfg != null && Files.isRegularFile(globalCfg)) {
            final List<String> global = Files.readAllLines(globalCfg, StandardCharsets.UTF_8);
            for (String key : new String[] {"WrapperCommand", "PostExitCommand"}) {
                final String v = value(global, key);
                if (v != null && !v.isBlank()) set.add(new String[] {key, v});
            }
        }
        Files.copy(instanceCfg, instanceCfg.resolveSibling("instance.cfg.modupdatechecker-backup"), StandardCopyOption.REPLACE_EXISTING);
        for (String[] kv : set) put(lines, kv[0], kv[1]);
        final Path tmp = instanceCfg.resolveSibling("instance.cfg.modupdatechecker-tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, instanceCfg, StandardCopyOption.REPLACE_EXISTING);
        return "pre-launch check added";
    }

    /** A key's value in the [General] section (where Prism keeps these), or null. */
    static String value(List<String> lines, String key) {
        boolean general = true; // Keys before any section header count as [General].
        for (String line : lines) {
            final String t = line.trim();
            if (t.startsWith("[")) {
                general = t.equalsIgnoreCase("[General]");
                continue;
            }
            if (general && t.startsWith(key + "=")) return t.substring(key.length() + 1);
        }
        return null;
    }

    /** Sets a key in [General], replacing it or adding it at the end of the section. */
    static void put(List<String> lines, String key, String value) {
        int sectionEnd = -1;
        boolean general = true;
        boolean sawGeneral = false;
        for (int i = 0; i < lines.size(); i++) {
            final String t = lines.get(i).trim();
            if (t.startsWith("[")) {
                if (general && sawGeneral && sectionEnd < 0) sectionEnd = i;
                general = t.equalsIgnoreCase("[General]");
                sawGeneral |= general;
                continue;
            }
            if (general && t.startsWith(key + "=")) {
                lines.set(i, key + "=" + value);
                return;
            }
        }
        if (!sawGeneral) {
            lines.add(0, "[General]");
            lines.add(1, key + "=" + value);
            return;
        }
        if (sectionEnd < 0) sectionEnd = lines.size();
        // Before the blank line that usually ends the section.
        while (sectionEnd > 0 && lines.get(sectionEnd - 1).trim().isEmpty()) sectionEnd--;
        lines.add(sectionEnd, key + "=" + value);
    }
}
