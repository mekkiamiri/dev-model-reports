package com.example.token;

/** Levée lorsqu'un jeton est refusé par {@link TokenService#verify(String)}. */
public class InvalidTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTokenException(String message) {
        super(message);
    }
}
