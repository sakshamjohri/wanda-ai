package com.saksham.wanda.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

    @Test
    void keepsH2DatabasesOpenBetweenQueries() {
        assertEquals("jdbc:h2:./data/wanda;DB_CLOSE_DELAY=-1", AppConfig.keepDatabaseOpen("jdbc:h2:./data/wanda"));
    }

    @Test
    void leavesOtherUrlsAndExplicitSettingsAlone() {
        assertEquals("jdbc:postgresql://db/wanda", AppConfig.keepDatabaseOpen("jdbc:postgresql://db/wanda"));
        assertEquals("jdbc:h2:mem:t;DB_CLOSE_DELAY=10", AppConfig.keepDatabaseOpen("jdbc:h2:mem:t;DB_CLOSE_DELAY=10"));
    }

    @Test
    void recognisesXaiKeysByTheirPrefix() {
        assertEquals(true, new AppConfig(8080, "u", "xai-abc", "d", "", "").looksLikeXaiKey());
        assertEquals(false, new AppConfig(8080, "u", "gsk_abc", "d", "", "").looksLikeXaiKey());
    }
}
