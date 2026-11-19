package com.example.token;

/** Levée quand un jeton n'est pas valide (format, signature ou expiration). */
public class InvalidTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTokenException(String message) {
        super(message);
    }
}
