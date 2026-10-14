package com.saksham.wanda.exception;

/** Raised for failed logins, invalid sign-ups and requests without a valid session. */
public class AuthException extends AppException {

    public AuthException(String message) {
        super(401, message);
    }
}
