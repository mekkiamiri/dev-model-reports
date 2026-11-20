package com.example.files;

public class InvalidNameException extends IllegalArgumentException {
    public InvalidNameException(String message) {
        super(message);
    }
}
