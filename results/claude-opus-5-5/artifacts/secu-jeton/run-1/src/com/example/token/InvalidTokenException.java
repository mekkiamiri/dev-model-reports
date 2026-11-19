package com.example.token;

/** Levée lorsqu'un jeton est refusé. Le message ne reprend jamais le contenu du jeton. */
public class InvalidTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTokenException(String message) {
        super(message);
    }
}
