package dev.rinchan.waystonesitemcost.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency TOML reader/parser supporting top-level keys, [section] headers,
 * booleans, quoted strings, integers, and single-line or multi-line string arrays.
 */
public final class SimpleToml {
    private final Map<String, Object> values;

    private SimpleToml(Map<String, Object> values) {
        this.values = values;
    }

    public static SimpleToml parse(String content) {
        var map = new LinkedHashMap<String, Object>();
        if (content == null || content.isEmpty()) {
            return new SimpleToml(map);
        }
        String section = "";
        var lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]") && !line.startsWith("[[")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String rawVal = line.substring(eq + 1).trim();
            // Handle multi-line array values
            if (rawVal.startsWith("[") && !rawVal.endsWith("]")) {
                var sb = new StringBuilder(rawVal);
                while (i + 1 < lines.length && !sb.toString().trim().endsWith("]")) {
                    i++;
                    String next = stripComment(lines[i]).trim();
                    if (!next.isEmpty()) {
                        sb.append(' ').append(next);
                    }
                }
                rawVal = sb.toString().trim();
            }
            String fullKey = section.isEmpty() ? key : section + "." + key;
            map.put(fullKey, parseValue(rawVal));
        }
        return new SimpleToml(map);
    }

    private static String stripComment(String line) {
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inDouble) {
                escaped = true;
                continue;
            }
            if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '#' && !inDouble && !inSingle) return line.substring(0, i);
        }
        return line;
    }

    private static Object parseValue(String raw) {
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return parseStringArray(raw.substring(1, raw.length() - 1));
        }
        if ((raw.startsWith("\"") && raw.endsWith("\"")) || (raw.startsWith("'") && raw.endsWith("'"))) {
            return unquote(raw);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private static List<String> parseStringArray(String inner) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        boolean escaped = false;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inDouble) {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
            } else if (c == ',' && !inDouble && !inSingle) {
                String token = current.toString().trim();
                if (!token.isEmpty()) result.add(unquote(token));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) result.add(unquote(last));
        return List.copyOf(result);
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            String body = token.substring(1, token.length() - 1);
            var sb = new StringBuilder(body.length());
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < body.length()) {
                    char next = body.charAt(++i);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (token.length() >= 2 && token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    public static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object val = values.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    public String getString(String key, String fallback) {
        Object val = values.get(key);
        return val != null ? val.toString() : fallback;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key, List<String> fallback) {
        Object val = values.get(key);
        if (val instanceof List<?> list) {
            var out = new ArrayList<String>(list.size());
            for (var item : list) {
                if (item != null) out.add(item.toString());
            }
            return List.copyOf(out);
        }
        return fallback;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }
}
