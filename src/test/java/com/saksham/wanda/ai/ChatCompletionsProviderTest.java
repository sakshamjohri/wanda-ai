package com.saksham.wanda.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.saksham.wanda.exception.AIException;
import com.saksham.wanda.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionsProviderTest {

    private final GroqProvider groq = new GroqProvider();

    private static Message user(String text) {
        return new Message(1, Message.Role.USER, text);
    }

    private static ChatRequest request(String system, List<Message> history) {
        return new ChatRequest(history, system, "", 0.7, "gsk_test", "low");
    }

    /* ------------------------------------------------ streamed chunks */

    @Test
    void readsTheTextOfAStreamedChunk() {
        assertEquals("Hi", groq.deltaText("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"));
    }

    @Test
    void chunksWithoutTextGiveNothing() {
        assertEquals("", groq.deltaText("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"));
        assertEquals("", groq.deltaText("{\"choices\":[{\"delta\":{\"reasoning\":\"thinking\"}}]}"));   // reasoning is never shown
        assertEquals("", groq.deltaText("{\"choices\":[],\"usage\":{\"total_tokens\":3}}"));
        assertEquals("", groq.deltaText("{\"choices\":[{\"delta\":{\"content\":null}}]}"));
    }

    @Test
    void anErrorChunkStopsTheReply() {
        AIException e = assertThrows(AIException.class, () -> groq.deltaText("{\"error\":{\"message\":\"boom\"}}"));
        assertTrue(e.getMessage().contains("boom"));
    }

    @Test
    void garbageIsReportedAsAnUnexpectedResponse() {
        assertThrows(AIException.class, () -> groq.deltaText("not json"));
    }

    /* ------------------------------------------------ request body */

    @Test
    void bodyHasSystemPromptHistoryAndStreamFlag() {
        JsonObject body = groq.buildBody(request("Be brief.", List.of(user("hello"))), "openai/gpt-oss-120b", true, null);
        assertEquals("openai/gpt-oss-120b", body.get("model").getAsString());
        assertTrue(body.get("stream").getAsBoolean());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("hello", messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void aBlankSystemPromptIsLeftOut() {
        JsonObject body = groq.buildBody(request("  ", List.of(user("hello"))), "m", false, null);
        assertEquals(1, body.getAsJsonArray("messages").size());
        assertFalse(body.has("stream"));
    }

    @Test
    void gptOssModelsGetFastReasoningAndOthersGetNone() {
        JsonObject extra = groq.extraParams("openai/gpt-oss-120b", "medium");
        assertEquals("medium", extra.get("reasoning_effort").getAsString());
        assertFalse(extra.get("include_reasoning").getAsBoolean());
        assertEquals("low", groq.extraParams("openai/gpt-oss-20b", null).get("reasoning_effort").getAsString());
        assertNull(groq.extraParams("qwen/qwen3.8-27b", "low"));
    }

    @Test
    void onlyTheNewestPictureMessageIsSentWithItsPictures() {
        Message first = user("look at this");
        first.setImages("data:image/png;base64,AAAA");
        Message reply = new Message(1, Message.Role.ASSISTANT, "A red square.");
        Message second = user("and this one?");
        second.setImages("data:image/png;base64,BBBB\ndata:image/png;base64,CCCC");
        ChatRequest req = request("", List.of(first, reply, second));

        assertEquals(second, req.pictureMessage());
        JsonArray messages = groq.buildBody(req, "qwen/qwen3.8-27b", false, null).getAsJsonArray("messages");
        assertTrue(messages.get(0).getAsJsonObject().get("content").isJsonPrimitive());          // old picture message: text only
        JsonArray parts = messages.get(2).getAsJsonObject().getAsJsonArray("content");
        assertEquals(3, parts.size());                                                           // 2 pictures + the text
        assertEquals("image_url", parts.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void picturesSelectTheVisionModelAndFallbackModelsAreHandled() {
        ChatRequest withPicture = request("", List.of(pictureMessage()));
        assertEquals("qwen/qwen3.8-27b", groq.resolveModel("", withPicture.pictureMessage() != null));
        assertEquals("openai/gpt-oss-120b", groq.resolveModel("", false));
        assertEquals("openai/gpt-oss-20b", groq.resolveModel("openai/gpt-oss-20b", false));
        assertEquals("openai/gpt-oss-120b", groq.resolveModel("grok-3", false));                  // leftover id from another provider
        assertNotNull(withPicture.pictureMessage());
    }

    private static Message pictureMessage() {
        Message m = user("what is this?");
        m.setImages("data:image/png;base64,AAAA");
        return m;
    }

    @Test
    void generatedPicturesAreDescribedToTheChatModelAsText() {
        Message asked = new Message(1, Message.Role.USER, "a red fox");
        asked.setKind("image");
        Message made = new Message(1, Message.Role.ASSISTANT, "a red fox");
        made.setKind("image");
        assertEquals("Create an image: a red fox", asked.promptText());
        assertTrue(made.promptText().startsWith("[Created an image"));
    }
}
