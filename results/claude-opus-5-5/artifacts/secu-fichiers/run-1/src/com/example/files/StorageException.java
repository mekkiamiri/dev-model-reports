package com.example.files;

/** Erreur d'entrée-sortie (ou lien symbolique refusé) lors de l'accès au stockage. */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
