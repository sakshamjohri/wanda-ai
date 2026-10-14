package com.saksham.wanda.ai;

import com.saksham.wanda.exception.AIException;

/**
 * A service that turns a text description into a picture. Like {@link AIProvider}, the chat
 * service only sees this interface, so another service could be added without touching it.
 */
public interface ImageProvider {

    /** A finished picture: its MIME type and bytes. */
    record GeneratedImage(String mime, byte[] bytes) { }

    /** Display name shown in the UI. */
    String name();

    /** Creates a picture for the prompt using the given credentials. */
    GeneratedImage generate(String prompt, String accountId, String apiToken) throws AIException;
}
