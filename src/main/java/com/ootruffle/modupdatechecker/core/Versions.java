package com.ootruffle.modupdatechecker.core;

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
