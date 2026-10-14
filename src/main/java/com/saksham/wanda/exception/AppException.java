package com.saksham.wanda.exception;

/**
 * Base class for every expected failure in the application.
 * Each subclass carries the HTTP status the web layer should answer with,
 * so servlets can handle all of them polymorphically in one place.
 *
 * @author Saksham Johri
 */
public class AppException extends RuntimeException {

    private final int status;

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AppException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
