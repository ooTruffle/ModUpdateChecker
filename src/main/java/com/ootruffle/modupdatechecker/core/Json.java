package com.ootruffle.modupdatechecker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the checker needs only the JDK (it also runs before the game
 * starts, where Minecraft's Gson isn't there). Objects are {@link LinkedHashMap}s, arrays
 * {@link ArrayList}s, numbers {@link Double}s.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        final Json p = new Json(text);
        p.ws();
        final Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    // ---- reading helpers ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.emptyList();
    }

    public static Map<String, Object> obj(Map<String, Object> o, String key) {
        return obj(o.get(key));
    }

    public static List<Object> arr(Map<String, Object> o, String key) {
        return arr(o.get(key));
    }

    /** The value as text ("" when missing or null). */
    public static String str(Map<String, Object> o, String key) {
        final Object v = o.get(key);
        if (v == null) return "";
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return Long.toString(d.longValue());
        return v.toString();
    }

    public static boolean bool(Map<String, Object> o, String key, boolean def) {
        return o.get(key) instanceof Boolean b ? b : def;
    }

    public static long num(Map<String, Object> o, String key, long def) {
        return o.get(key) instanceof Double d ? d.longValue() : def;
    }

    // ---- writing ----

    public static String write(Object v, boolean pretty) {
        final StringBuilder sb = new StringBuilder();
        write(sb, v, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            final double d = n.doubleValue();
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append('}');
        } else if (v instanceof Iterable<?> list) {
            if (!list.iterator().hasNext()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                write(sb, o, pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) return;
        sb.append('\n');
        for (int k = 0; k < depth; k++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            final char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        final char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        final Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected a key");
            final String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            final char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        final List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            final char c = next();
            if (c == ']') return a;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        i++;
        final StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            final char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            final char e = next();
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Double number() {
        final int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private char next() {
        if (i >= s.length()) throw error("unexpected end");
        return s.charAt(i++);
    }

    private void expect(char c) {
        if (next() != c) throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String why) {
        return new IllegalArgumentException("Bad JSON at " + i + ": " + why);
    }
}
