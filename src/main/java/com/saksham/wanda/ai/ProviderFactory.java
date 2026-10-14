package com.saksham.wanda.ai;

/**
 * Picks the right {@link AIProvider} at runtime: Groq when an API key is available,
 * otherwise the setup guide. Callers only ever see the interface type.
 */
public final class ProviderFactory {

    private final AIProvider groq = new GroqProvider();
    private final AIProvider setupGuide = new SetupGuideProvider();
    private final ImageProvider images = new CloudflareImageProvider();

    public AIProvider textProvider(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? setupGuide : groq;
    }

    /** The service that creates pictures. */
    public ImageProvider imageProvider() {
        return images;
    }
}
