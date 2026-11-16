package com.example.invoice;

public class InvoiceFormatException extends RuntimeException {
    private final int lineNumber;

    public InvoiceFormatException(int lineNumber, String message) {
        super("ligne " + lineNumber + " : " + message);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
