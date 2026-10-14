package com.saksham.wanda.ai;

import com.saksham.wanda.exception.AIException;

import java.util.function.Consumer;

/**
 * A text model that can answer a conversation. Implementations are chosen at
 * runtime by {@link ProviderFactory}, so the chat service never depends on a vendor.
 */
public interface AIProvider {

    /** Display name shown in the UI. */
    String name();

    /** Produces the assistant's next message in one piece. */
    String reply(ChatRequest request) throws AIException;

    /**
     * Produces the next message piece by piece, calling {@code onDelta} for every new bit of text,
     * and returns the full text. Providers that cannot stream deliver everything as one piece.
     * If {@code onDelta} throws, streaming stops and the exception propagates.
     */
    default String stream(ChatRequest request, Consumer<String> onDelta) throws AIException {
        String full = reply(request);
        onDelta.accept(full);
        return full;
    }
}
