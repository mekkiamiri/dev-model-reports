package com.example.config;

/** Erreur de lecture, de fusion ou de résolution de configuration. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
