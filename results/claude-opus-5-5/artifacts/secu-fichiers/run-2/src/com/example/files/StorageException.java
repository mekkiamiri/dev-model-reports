package com.example.files;

/** Levée pour toute erreur d'entrée-sortie (ou lien symbolique refusé) lors du stockage. */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
