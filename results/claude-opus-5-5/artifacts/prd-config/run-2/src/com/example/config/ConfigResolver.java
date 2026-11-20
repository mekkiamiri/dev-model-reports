package com.example.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fusion de couches de configuration et résolution des variables. */
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
            parseLayerInto(layer, layerIndex, merged);
        }
        return new Resolver(merged).resolveAll();
    }

    private static void parseLayerInto(String layer, int layerIndex, Map<String, String> merged) {
        String[] lines = layer.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == '!') {
                continue;
            }
            String where = "layer " + layerIndex + ", line " + (i + 1);
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ConfigException("missing '=' at " + where + ": " + stripped);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.isEmpty()) {
                throw new ConfigException("empty key at " + where);
            }
            if (value.equals(UNSET)) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        }
    }

    /** Résolution des variables avec mémoïsation et détection de cycles. */
    private static final class Resolver {
        private final Map<String, String> raw;
        private final Map<String, String> resolved = new HashMap<>();
        private final Set<String> inProgress = new HashSet<>();

        Resolver(Map<String, String> raw) {
            this.raw = raw;
        }

        Map<String, String> resolveAll() {
            Map<String, String> result = new LinkedHashMap<>();
            try {
                for (String key : raw.keySet()) {
                    result.put(key, resolveKey(key));
                }
            } catch (StackOverflowError e) {
                throw new ConfigException("variable chain too deep");
            }
            return result;
        }

        private String resolveKey(String key) {
            String done = resolved.get(key);
            if (done != null) {
                return done;
            }
            if (!inProgress.add(key)) {
                throw new ConfigException("variable cycle detected involving key '" + key + "'");
            }
            String value = interpolate(raw.get(key), key);
            inProgress.remove(key);
            resolved.put(key, value);
            return value;
        }

        private String interpolate(String value, String owner) {
            StringBuilder out = new StringBuilder(value.length());
            int n = value.length();
            int i = 0;
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
                        throw new ConfigException("unterminated '${' in value of key '" + owner + "'");
                    }
                    String expr = value.substring(i + 2, close);
                    String name;
                    String def = null;
                    int sep = expr.indexOf(":-");
                    if (sep >= 0) {
                        name = expr.substring(0, sep);
                        def = expr.substring(sep + 2);
                    } else {
                        name = expr;
                    }
                    if (name.isEmpty()) {
                        throw new ConfigException("empty variable name in value of key '" + owner + "'");
                    }
                    if (raw.containsKey(name)) {
                        out.append(resolveKey(name));
                    } else if (def != null) {
                        out.append(def);
                    } else {
                        throw new ConfigException("unknown variable '" + name + "' in value of key '" + owner + "'");
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
