package com.saksham.wanda.ai;

/**
 * Used when no API key is configured. Instead of failing, it answers with
 * setup instructions so the rest of the app (history, files, voice, settings)
 * can still be explored.
 */
public class SetupGuideProvider implements AIProvider {

    @Override
    public String name() {
        return "Setup guide";
    }

    @Override
    public String reply(ChatRequest request) {
        return """
                **Wanda is almost ready.** No Groq API key is set up yet, so I can't answer \
                questions right now.

                To connect me (it's free):
                1. Create a key at **console.groq.com/keys**. It starts with `gsk_`.
                2. Open **Settings → Model** and paste it into *Groq API key*, \
                or paste it after `GROQ_API_KEY=` in the **wanda.properties** file and restart Wanda.

                Everything else already works: chat history, file uploads, voice and themes.""";
    }
}
