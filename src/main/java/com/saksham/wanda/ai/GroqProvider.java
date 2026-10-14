package com.saksham.wanda.ai;

import com.google.gson.JsonObject;

/**
 * Groq Cloud (free API keys at console.groq.com, keys start with "gsk_").
 * Model ids follow Groq's current recommendations; any Groq model can be chosen in Settings.
 *
 * @author Saksham Johri
 */
public class GroqProvider extends ChatCompletionsProvider {

    @Override
    public String name() {
        return "Groq";
    }

    @Override
    protected String endpoint() {
        return "https://api.groq.com/openai/v1/chat/completions";
    }

    @Override
    protected String defaultModel() {
        return "openai/gpt-oss-120b";
    }

    @Override
    protected String visionModel() {
        return "qwen/qwen3.8-27b";
    }

    @Override
    protected boolean supportsModel(String model) {
        return !model.startsWith("grok"); // ignore model ids left over from other providers
    }

    /**
     * The gpt-oss models think before they answer; "low" effort keeps replies quick, and the
     * thinking text is not returned. (Groq documents both settings for these models only.)
     */
    @Override
    protected JsonObject extraParams(String model, String effort) {
        if (!model.startsWith("openai/gpt-oss")) {
            return null;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("reasoning_effort", effort == null || effort.isBlank() ? "low" : effort);
        extra.addProperty("include_reasoning", false);
        return extra;
    }
}
