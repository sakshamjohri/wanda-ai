package com.saksham.wanda.exception;

/** Raised for bad user input: empty messages, oversized files, unknown ids and so on. */
public class ValidationException extends AppException {

    public ValidationException(String message) {
        super(400, message);
    }

    public ValidationException(int status, String message) {
        super(status, message);
    }
}
