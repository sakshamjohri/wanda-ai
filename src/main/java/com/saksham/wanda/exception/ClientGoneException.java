package com.saksham.wanda.exception;

/**
 * Raised while streaming a reply when the browser has disconnected (the user pressed Stop,
 * closed the tab or lost the connection). Not an error: the exchange is simply wrapped up.
 */
public class ClientGoneException extends AppException {

    public ClientGoneException(Throwable cause) {
        super(499, "Client disconnected", cause);
    }
}
