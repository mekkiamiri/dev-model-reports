package com.example.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fusionne des couches de configuration et remplace les variables. */
public final class ConfigResolver {

    private static final String UNSET = "!unset";

    private ConfigResolver() {
    }

    /** Couches de la moins prioritaire à la plus prioritaire ; résultat : clé -> valeur résolue. */
    public static Map<String, String> resolve(List<String> layers) {
        if (layers == null) {
            throw new ConfigException("layers list is null");
        }
        Map<String, String> merged = new LinkedHashMap<>();
        int index = 0;
        for (String layer : layers) {
            if (layer == null) {
                throw new ConfigException("layer " + index + " is null");
            }
            for (Map.Entry<String, String> e : parseLayer(layer, index).entrySet()) {
                if (UNSET.equals(e.getValue())) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            index++;
        }
        return new Interpolator(merged).resolveAll();
    }

    private static Map<String, String> parseLayer(String text, int layerIndex) {
        Map<String, String> props = new LinkedHashMap<>();
        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ConfigException("layer " + layerIndex + ", line " + (i + 1) + ": missing '='");
            }
            String key = line.substring(0, eq).strip();
            if (key.isEmpty()) {
                throw new ConfigException("layer " + layerIndex + ", line " + (i + 1) + ": empty key");
            }
            // Dans une même couche, la dernière occurrence l'emporte (y compris un !unset).
            props.put(key, line.substring(eq + 1).strip());
        }
        return props;
    }

    /** Résolution paresseuse avec mémorisation et détection de cycles. */
    private static final class Interpolator {
        private final Map<String, String> raw;
        private final Map<String, String> resolved = new HashMap<>();
        private final Set<String> inProgress = new LinkedHashSet<>();

        Interpolator(Map<String, String> raw) {
            this.raw = raw;
        }

        Map<String, String> resolveAll() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String key : raw.keySet()) {
                out.put(key, resolveKey(key));
            }
            return out;
        }

        private String resolveKey(String key) {
            String done = resolved.get(key);
            if (done != null) {
                return done;
            }
            if (!inProgress.add(key)) {
                List<String> path = new ArrayList<>(inProgress);
                List<String> cyc = new ArrayList<>(path.subList(path.indexOf(key), path.size()));
                cyc.add(key);
                throw new ConfigException("cycle detected: " + String.join(" -> ", cyc));
            }
            String value = interpolate(key, raw.get(key));
            inProgress.remove(key);
            resolved.put(key, value);
            return value;
        }

        private String interpolate(String owner, String value) {
            StringBuilder sb = new StringBuilder(value.length());
            int i = 0;
            int n = value.length();
            while (i < n) {
                char c = value.charAt(i);
                if (c != '$' || i + 1 >= n) {
                    sb.append(c);
                    i++;
                    continue;
                }
                char next = value.charAt(i + 1);
                if (next == '$') {
                    sb.append('$');
                    i += 2;
                } else if (next == '{') {
                    int close = value.indexOf('}', i + 2);
                    if (close < 0) {
                        throw new ConfigException("key '" + owner + "': unclosed '${'");
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
                        throw new ConfigException("key '" + owner + "': empty variable name");
                    }
                    if (raw.containsKey(name)) {
                        sb.append(resolveKey(name));
                    } else if (def != null) {
                        sb.append(def);
                    } else {
                        throw new ConfigException("key '" + owner + "': unknown variable '" + name + "'");
                    }
                    i = close + 1;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
    }
}
