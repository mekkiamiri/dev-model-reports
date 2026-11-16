package com.example.config;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ConfigResolver {
    private static final String UNSET = "!unset";

    private final Map<String, String> raw;
    private final Map<String, String> resolved = new HashMap<>();
    private final Set<String> inProgress = new LinkedHashSet<>();

    private ConfigResolver(Map<String, String> raw) {
        this.raw = raw;
    }

    public static Map<String, String> resolve(List<String> layers) {
        Map<String, String> merged = new HashMap<>();
        for (int i = 0; i < layers.size(); i++) {
            Map<String, String> layer = parseLayer(layers.get(i), i + 1);
            for (Map.Entry<String, String> e : layer.entrySet()) {
                if (e.getValue().equals(UNSET)) {
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        ConfigResolver resolver = new ConfigResolver(merged);
        Map<String, String> result = new TreeMap<>();
        for (String key : merged.keySet()) {
            result.put(key, resolver.value(key));
        }
        return result;
    }

    private static Map<String, String> parseLayer(String text, int layerNumber) {
        Map<String, String> layer = new HashMap<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ConfigException("couche " + layerNumber + ", ligne " + (i + 1) + " : '=' manquant");
            }
            String key = line.substring(0, eq).trim();
            if (key.isEmpty()) {
                throw new ConfigException("couche " + layerNumber + ", ligne " + (i + 1) + " : clé vide");
            }
            layer.put(key, line.substring(eq + 1).trim());
        }
        return layer;
    }

    private String value(String key) {
        String done = resolved.get(key);
        if (done != null) {
            return done;
        }
        if (!inProgress.add(key)) {
            throw new ConfigException("cycle : " + String.join(" -> ", inProgress) + " -> " + key);
        }
        String result = interpolate(raw.get(key), key);
        inProgress.remove(key);
        resolved.put(key, result);
        return result;
    }

    private String interpolate(String text, String owner) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$' || i + 1 >= text.length()) {
                out.append(c);
                i++;
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i += 2;
            } else if (next == '{') {
                int close = text.indexOf('}', i + 2);
                if (close < 0) {
                    throw new ConfigException(owner + " : '${' sans '}'");
                }
                String expr = text.substring(i + 2, close);
                int sep = expr.indexOf(":-");
                String name = (sep < 0 ? expr : expr.substring(0, sep)).trim();
                if (name.isEmpty()) {
                    throw new ConfigException(owner + " : nom de variable vide");
                }
                if (raw.containsKey(name)) {
                    out.append(value(name));
                } else if (sep >= 0) {
                    out.append(expr.substring(sep + 2));
                } else {
                    throw new ConfigException(owner + " : variable inconnue " + name);
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
