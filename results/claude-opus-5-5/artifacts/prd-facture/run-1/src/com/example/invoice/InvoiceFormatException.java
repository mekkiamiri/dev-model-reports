package com.example.invoice;

/** Erreur de format du CSV, portant le numéro de la ligne fautive (1 = en-tête). */
public class InvoiceFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    public InvoiceFormatException(int lineNumber, String message) {
        super("line " + lineNumber + ": " + message);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
