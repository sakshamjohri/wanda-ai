package com.saksham.wanda.exception;

/** Wraps SQLExceptions so the DAO layer exposes a single unchecked failure type. */
public class DataAccessException extends AppException {

    public DataAccessException(String message, Throwable cause) {
        super(500, message, cause);
    }
}
