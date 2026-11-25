package com.ootruffle.modupdatechecker.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Compares mod versions with release tags (semantic versions where possible). */
public final class Versions {

    /** Loader and game-version tags in version strings, stripped before comparing. */
    private static final Pattern NOISE = Pattern.compile("(?i)[-_+.]?(mc)?1\\.8\\.9|[-_+.]?(ornithe|fabric|legacy-fabric)");
    private static final Pattern SEMVER = Pattern.compile("\\d+(\\.\\d+)*(-[0-9A-Za-z.-]+)?");

    private Versions() {}

    /**
     * Negative if {@code tag} is newer than {@code installed}, 0 if the same, positive if older.
     * Loader and game-version tags are ignored ("v1.2.0+1.8.9-ornithe" is 1.2.0).
     */
    public static int compare(String installed, String tag) {
        final String a = clean(installed);
        final String b = clean(tag);
        if (a.equalsIgnoreCase(b)) return 0;
        if (SEMVER.matcher(a).matches() && SEMVER.matcher(b).matches()) return semver(a, b);
        // Not semantic versions: different means newer, unless one names the other.
        final String la = a.toLowerCase(Locale.ROOT);
        final String lb = b.toLowerCase(Locale.ROOT);
        return la.contains(lb) || lb.contains(la) ? 0 : -1;
    }

    private static int semver(String a, String b) {
        final int da = a.indexOf('-');
        final int db = b.indexOf('-');
        final String[] na = (da < 0 ? a : a.substring(0, da)).split("\\.");
        final String[] nb = (db < 0 ? b : b.substring(0, db)).split("\\.");
        for (int k = 0; k < Math.max(na.length, nb.length); k++) {
            final long x = k < na.length ? Long.parseLong(na[k]) : 0;
            final long y = k < nb.length ? Long.parseLong(nb[k]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        // Same numbers: a pre-release ("-beta.2") comes before the release.
        if (da < 0 && db < 0) return 0;
        if (da < 0) return 1;
        if (db < 0) return -1;
        final String[] pa = a.substring(da + 1).split("\\.");
        final String[] pb = b.substring(db + 1).split("\\.");
        for (int k = 0; k < Math.min(pa.length, pb.length); k++) {
            final boolean numA = pa[k].matches("\\d+");
            final boolean numB = pb[k].matches("\\d+");
            final int c = numA && numB ? Long.compare(Long.parseLong(pa[k]), Long.parseLong(pb[k]))
                    : numA ? -1 : numB ? 1 : pa[k].compareToIgnoreCase(pb[k]);
            if (c != 0) return c;
        }
        return Integer.compare(pa.length, pb.length);
    }

    /**
     * Whether {@code version} (a Minecraft version like "1.8.9") satisfies a fabric.mod.json
     * dependency: a string, or a list of strings any of which may match. Each string is one or more
     * space-separated conditions that all must hold: {@code *}, {@code 1.8.9}, {@code =1.8.9},
     * {@code >=1.8}, {@code <1.9}, {@code ~1.8.9}, {@code ^1.8}, {@code 1.8.x}. Anything it can't
     * read counts as a match, so an unusual (but valid) dependency never blocks an update.
     */
    public static boolean matches(String version, Object dependency) {
        if (dependency == null) return true;
        if (dependency instanceof List<?> list) {
            if (list.isEmpty()) return true;
            for (Object o : list) if (o instanceof String s && matchesOne(version, s)) return true;
            return false;
        }
        return !(dependency instanceof String s) || matchesOne(version, s);
    }

    private static boolean matchesOne(String version, String predicate) {
        final String p = predicate.trim();
        if (p.isEmpty() || p.equals("*")) return true;
        for (String part : p.split("\\s+")) {
            final Boolean ok = condition(version, part);
            if (ok == null) return true; // Unreadable: don't block.
            if (!ok) return false;
        }
        return true;
    }

    private static Boolean condition(String version, String part) {
        String op = "";
        for (String o : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
            if (part.startsWith(o)) {
                op = o;
                break;
            }
        }
        final String target = part.substring(op.length());
        final long[] have = numbers(version);
        if (have == null) return null;
        // Wildcards ("1.8.x", "1.*"): the parts before the wildcard must be equal.
        if (target.matches("[0-9.]*[xX*](\\..*)?")) {
            if (!op.isEmpty() && !op.equals("=")) return null;
            final String[] t = target.split("\\.");
            for (int k = 0; k < t.length; k++) {
                if (t[k].equalsIgnoreCase("x") || t[k].equals("*")) return true;
                final long n;
                try {
                    n = Long.parseLong(t[k]);
                } catch (NumberFormatException e) {
                    return null;
                }
                if ((k < have.length ? have[k] : 0) != n) return false;
            }
            return true;
        }
        final long[] want = numbers(target);
        if (want == null) return null;
        final int c = compareNumbers(have, want);
        return switch (op) {
            case "", "=" -> c == 0;
            case ">=" -> c >= 0;
            case ">" -> c > 0;
            case "<=" -> c <= 0;
            case "<" -> c < 0;
            // Same minor ("~1.8.9": 1.8.9 up to, not including, 1.9).
            case "~" -> c >= 0 && have[0] == want[0] && (want.length < 2 || (have.length > 1 ? have[1] : 0) == want[1]);
            // Same major ("^1.8": 1.8 up to, not including, 2.0).
            case "^" -> c >= 0 && have[0] == want[0];
            default -> null;
        };
    }

    /** "1.8.9" → [1, 8, 9]; pre-release and build parts are dropped. Null if it isn't numeric. */
    private static long[] numbers(String v) {
        String s = v.trim();
        final int cut = indexOfAny(s, '-', '+');
        if (cut >= 0) s = s.substring(0, cut);
        if (s.isEmpty()) return null;
        final String[] parts = s.split("\\.");
        final long[] out = new long[parts.length];
        for (int k = 0; k < parts.length; k++) {
            try {
                out[k] = Long.parseLong(parts[k]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static int indexOfAny(String s, char a, char b) {
        final int i = s.indexOf(a);
        final int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    private static int compareNumbers(long[] a, long[] b) {
        for (int k = 0; k < Math.max(a.length, b.length); k++) {
            final long x = k < a.length ? a[k] : 0;
            final long y = k < b.length ? b[k] : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    static String clean(String v) {
        String s = v.trim();
        final int plus = s.indexOf('+');
        if (plus > 0) s = s.substring(0, plus);
        s = NOISE.matcher(s).replaceAll("");
        while (!s.isEmpty() && !Character.isDigit(s.charAt(0))) s = s.substring(1); // "v1.0", "release-1.0"
        s = s.replaceAll("[-_.]+$", "");
        return s.isEmpty() ? v.trim() : s;
    }
}
