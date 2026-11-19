package com.example.files;

/** Levée quand un nom de pièce jointe est refusé (avant tout accès disque). */
public class InvalidNameException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidNameException(String message) {
        super(message);
    }
}
