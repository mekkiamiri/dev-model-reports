package com.example.config;

/** Erreur de lecture, de fusion ou d'interpolation de la configuration. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
