package com.saksham.wanda.exception;

/** Raised when the language model cannot produce a result. */
public class AIException extends AppException {

    public AIException(String message) {
        super(502, message);
    }

    public AIException(String message, Throwable cause) {
        super(502, message, cause);
    }
}
