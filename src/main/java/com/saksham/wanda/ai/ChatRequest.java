package com.saksham.wanda.ai;

import com.saksham.wanda.model.Message;

import java.util.List;

/**
 * Everything a language model needs for one reply.
 *
 * @param history      previous messages, oldest first, ending with the new user message
 * @param systemPrompt instructions that shape the assistant's behaviour
 * @param model        model id chosen in Settings; blank means the provider default
 * @param temperature  sampling temperature 0.0 - 1.5
 * @param apiKey       key used for this call
 * @param effort       how much the model should think before answering: low, medium or high
 */
public record ChatRequest(List<Message> history, String systemPrompt, String model,
                          double temperature, String apiKey, String effort) {

    /**
     * The newest user message that carries pictures (loaded into {@link Message#getImages()}), or null.
     * Only that message's pictures are sent, which keeps every request within the provider's image limit.
     */
    public Message pictureMessage() {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m.getRole() == Message.Role.USER && !m.pictureList().isEmpty()) {
                return m;
            }
        }
        return null;
    }
}
