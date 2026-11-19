package com.example.files;

/** Erreur d'entrée-sortie ou de sécurité (lien symbolique...) lors d'un accès au stockage. */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
