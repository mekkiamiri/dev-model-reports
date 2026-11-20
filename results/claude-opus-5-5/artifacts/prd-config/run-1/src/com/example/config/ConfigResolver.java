package com.example.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fusionne des couches de configuration au format {@code clé = valeur}
 * puis interprète les variables {@code ${nom}}, {@code ${nom:-défaut}} et {@code $$}.
 */
public final class ConfigResolver {

    private static final String UNSET = "!unset";

    private ConfigResolver() {
    }

    /** Couches de la moins prioritaire à la plus prioritaire ; résultat : clé -> valeur résolue. */
    public static Map<String, String> resolve(List<String> layers) {
        if (layers == null) {
            throw new ConfigException("layers must not be null");
        }
        Map<String, String> merged = new LinkedHashMap<>();
        int layerIndex = 0;
        for (String layer : layers) {
            layerIndex++;
            if (layer == null) {
                throw new ConfigException("layer " + layerIndex + " is null");
            }
            applyLayer(layer, layerIndex, merged);
        }
        return new Resolver(merged).resolveAll();
    }

    // ------------------------------------------------------------------ parsing

    private static void applyLayer(String layer, int layerIndex, Map<String, String> merged) {
        String[] lines = layer.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == '!') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ConfigException(where(layerIndex, i) + "missing '=' in line: " + line);
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.isEmpty()) {
                throw new ConfigException(where(layerIndex, i) + "empty key in line: " + line);
            }
            if (value.equals(UNSET)) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        }
    }

    private static String where(int layerIndex, int lineIndex) {
        return "layer " + layerIndex + ", line " + (lineIndex + 1) + ": ";
    }

    // --------------------------------------------------------------- resolution

    private static final class Resolver {
        private final Map<String, String> raw;
        private final Map<String, String> resolved = new HashMap<>();
        private final Set<String> inProgress = new HashSet<>();
        private final Deque<String> stack = new ArrayDeque<>();

        Resolver(Map<String, String> raw) {
            this.raw = raw;
        }

        Map<String, String> resolveAll() {
            Map<String, String> result = new TreeMap<>();
            for (String key : raw.keySet()) {
                result.put(key, resolveKey(key));
            }
            return result;
        }

        private String resolveKey(String key) {
            String done = resolved.get(key);
            if (done != null) {
                return done;
            }
            if (inProgress.contains(key)) {
                List<String> chain = new ArrayList<>();
                boolean started = false;
                // stack is LIFO: iterate from oldest to newest
                List<String> ordered = new ArrayList<>(stack);
                java.util.Collections.reverse(ordered);
                for (String k : ordered) {
                    if (k.equals(key)) {
                        started = true;
                    }
                    if (started) {
                        chain.add(k);
                    }
                }
                chain.add(key);
                throw new ConfigException("variable cycle detected: " + String.join(" -> ", chain));
            }
            inProgress.add(key);
            stack.push(key);
            String value = interpolate(key, raw.get(key));
            stack.pop();
            inProgress.remove(key);
            resolved.put(key, value);
            return value;
        }

        private String interpolate(String ownerKey, String value) {
            StringBuilder out = new StringBuilder(value.length());
            int i = 0;
            int n = value.length();
            while (i < n) {
                char c = value.charAt(i);
                if (c != '$' || i + 1 >= n) {
                    out.append(c);
                    i++;
                    continue;
                }
                char next = value.charAt(i + 1);
                if (next == '$') {
                    out.append('$');
                    i += 2;
                } else if (next == '{') {
                    int close = value.indexOf('}', i + 2);
                    if (close < 0) {
                        throw new ConfigException("unterminated '${' in value of key '" + ownerKey + "': " + value);
                    }
                    String body = value.substring(i + 2, close);
                    String name;
                    String defaultValue = null;
                    int sep = body.indexOf(":-");
                    if (sep >= 0) {
                        name = body.substring(0, sep);
                        defaultValue = body.substring(sep + 2);
                    } else {
                        name = body;
                    }
                    if (name.isEmpty()) {
                        throw new ConfigException("empty variable name in value of key '" + ownerKey + "': " + value);
                    }
                    if (raw.containsKey(name)) {
                        out.append(resolveKey(name));
                    } else if (defaultValue != null) {
                        out.append(defaultValue);
                    } else {
                        throw new ConfigException("unknown variable '" + name + "' in value of key '" + ownerKey + "'");
                    }
                    i = close + 1;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }
    }
}
