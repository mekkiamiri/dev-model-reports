package com.example.invoice;

/** Erreur de format du CSV, avec le numéro de la ligne fautive (1 = en-tête). */
public class InvoiceFormatException extends RuntimeException {
    private final int lineNumber;

    public InvoiceFormatException(int lineNumber, String message) {
        super("Ligne " + lineNumber + " : " + message);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
