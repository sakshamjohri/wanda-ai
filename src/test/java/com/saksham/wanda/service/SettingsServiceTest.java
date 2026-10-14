package com.saksham.wanda.service;

import com.saksham.wanda.TestDb;
import com.saksham.wanda.db.SettingsDao;
import com.saksham.wanda.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {

    private final SettingsService settings =
            new SettingsService(new SettingsDao(TestDb.db()), "gsk_server", "abcdef0123456789", "server_cloudflare_token_000");

    @Test
    void newUsersGetTheWandaDefaults() {
        long id = TestDb.newUser().getId();
        assertEquals("dark", settings.get(id).get("theme"));
        assertEquals("fast", settings.get(id).get("thinking"));
        assertEquals("true", settings.get(id).get("voiceFeatures"));
        assertEquals("true", settings.get(id).get("voiceReplies"));
    }

    @Test
    void savedValuesComeBackAndSurviveACacheReload() {
        long id = TestDb.newUser().getId();
        settings.update(id, Map.of("theme", "light", "temperature", "1.2", "voiceReplies", "false"));
        assertEquals("light", settings.get(id).get("theme"));
        // a second service instance reads from the database, not from the first one's cache
        SettingsService fresh = new SettingsService(new SettingsDao(TestDb.db()), "");
        assertEquals("false", fresh.get(id).get("voiceReplies"));
        assertEquals("1.2", fresh.get(id).get("temperature"));
    }

    @Test
    void rejectsInvalidValues() {
        long id = TestDb.newUser().getId();
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("theme", "purple")));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("temperature", "9")));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("thinking", "turbo")));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("voiceFeatures", "maybe")));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("systemPrompt", "x".repeat(4001))));
    }

    @Test
    void refusesKeysFromTheWrongService() {
        long id = TestDb.newUser().getId();
        ValidationException xai = assertThrows(ValidationException.class, () -> settings.update(id, Map.of("apiKey", "xai-abc")));
        assertTrue(xai.getMessage().contains("Groq"));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("imageToken", "gsk_thisisagroqkey000000000000")));
        assertThrows(ValidationException.class, () -> settings.update(id, Map.of("imageAccountId", "bad id!")));
    }

    @Test
    void theBrowserNeverSeesSecretKeys() {
        long id = TestDb.newUser().getId();
        settings.update(id, Map.of("apiKey", "gsk_mine", "imageToken", "my_cloudflare_token_000000"));
        Map<String, Object> view = settings.publicView(id);
        assertFalse(view.containsKey("apiKey"));
        assertFalse(view.containsKey("imageToken"));
        assertEquals(true, view.get("hasPersonalKey"));
        assertEquals(true, view.get("hasPersonalImageKey"));
    }

    @Test
    void personalKeysWinOverServerKeys() {
        long id = TestDb.newUser().getId();
        assertEquals("gsk_server", settings.apiKeyFor(id));
        assertEquals("server_cloudflare_token_000", settings.imageCredentialsFor(id).token());
        settings.update(id, Map.of("apiKey", "gsk_mine", "imageAccountId", "11112222aaaabbbb", "imageToken", "my_cloudflare_token_000000"));
        assertEquals("gsk_mine", settings.apiKeyFor(id));
        assertEquals("my_cloudflare_token_000000", settings.imageCredentialsFor(id).token());
    }

    @Test
    void replyStyleMapsToReasoningEffort() {
        long id = TestDb.newUser().getId();
        assertEquals("low", settings.effortFor(id));
        settings.update(id, Map.of("thinking", "balanced"));
        assertEquals("medium", settings.effortFor(id));
        settings.update(id, Map.of("thinking", "deep"));
        assertEquals("high", settings.effortFor(id));
    }
}
