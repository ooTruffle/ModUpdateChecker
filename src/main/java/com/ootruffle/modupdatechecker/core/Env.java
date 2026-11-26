package com.ootruffle.modupdatechecker.core;

import com.ootruffle.modupdatechecker.ModStatus;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Where the checker runs: in the game (set up from Fabric) or before it starts, as a launcher's
 * pre-launch command. Everything the checker needs from its surroundings comes from here, so it
 * only depends on the JDK.
 */
public final class Env {

    public static final String MOD_ID = "modupdatechecker";

    /** The game directory (the one holding {@code mods} and {@code config}). */
    public static volatile Path gameDir = Paths.get(".").toAbsolutePath().normalize();

    public static volatile String minecraftVersion = "1.8.9";

    /** Running in the game (false before launch, in the pre-launch check). */
    public static volatile boolean inGame;

    /** Finds the installed mods. */
    public static volatile Supplier<List<ModStatus>> scanner = Collections::emptyList;

    /** This mod's own jar (or, in dev runs, its class and resource folders). */
    public static volatile List<Path> selfPaths = Collections.emptyList();

    public static volatile Log log = Log.STDOUT;

    /** Log every mod's result (the pre-launch check only does with --verbose). */
    public static volatile boolean verbose = true;

    private Env() {}

    public static Path modsDir() {
        return gameDir.resolve("mods");
    }

    public static Path configDir() {
        return gameDir.resolve("config");
    }

    /** {@code config/modupdatechecker}. */
    public static Path dataDir() {
        return configDir().resolve(MOD_ID);
    }

    /** A plain logger: in the game it's log4j's, before it the console. */
    public interface Log {
        void info(String message, Object... args);

        void warn(String message, Object... args);

        Log STDOUT = new Log() {
            @Override
            public void info(String message, Object... args) {
                System.out.println("[ModUpdateChecker] " + format(message, args));
            }

            @Override
            public void warn(String message, Object... args) {
                System.out.println("[ModUpdateChecker] WARNING: " + format(message, args));
            }
        };

        /** Fills {@code {}} placeholders, like log4j (plain ASCII separators for launcher logs). */
        static String format(String message, Object... args) {
            return fill(message, args).replace(" · ", ", ");
        }

        private static String fill(String message, Object... args) {
            final StringBuilder sb = new StringBuilder();
            int arg = 0;
            int from = 0;
            int at;
            while ((at = message.indexOf("{}", from)) >= 0) {
                sb.append(message, from, at).append(arg < args.length ? String.valueOf(args[arg++]) : "{}");
                from = at + 2;
            }
            return sb.append(message.substring(from)).toString();
        }
    }
}
