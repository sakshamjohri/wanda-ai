package com.saksham.wanda.service;

import com.saksham.wanda.TestDb;
import com.saksham.wanda.ai.ProviderFactory;
import com.saksham.wanda.db.ConversationDao;
import com.saksham.wanda.db.MessageDao;
import com.saksham.wanda.db.SettingsDao;
import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import com.saksham.wanda.model.Conversation;
import com.saksham.wanda.model.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real service on an in-memory database. No API key is set, so replies come from the built-in setup guide (no network). */
class ChatServiceTest {

    private final SettingsService settings = new SettingsService(new SettingsDao(TestDb.db()), "");
    private final ChatService chat = new ChatService(new ConversationDao(TestDb.db()), new MessageDao(TestDb.db()), settings, new ProviderFactory());

    @AfterEach
    void stop() {
        chat.close();
    }

    @Test
    void aMessageCreatesAChatAndStoresBothSides() {
        long user = TestDb.newUser().getId();
        ChatService.ChatResult r = chat.send(user, null, "  hello wanda  ", List.of());
        assertEquals("hello wanda", r.conversation().getTitle());
        assertTrue(r.reply().getContent().contains("almost ready"));
        List<Message> history = chat.history(user, r.conversation().getId());
        assertEquals(List.of(Message.Role.USER, Message.Role.ASSISTANT), history.stream().map(Message::getRole).toList());
    }

    @Test
    void emptyAndOversizedMessagesAreRejected() {
        long user = TestDb.newUser().getId();
        assertThrows(ValidationException.class, () -> chat.send(user, null, "   ", List.of()));
        assertThrows(ValidationException.class, () -> chat.send(user, null, "x".repeat(20_001), List.of()));
    }

    @Test
    void morethanThreePicturesAreRefused() {
        long user = TestDb.newUser().getId();
        List<Attachment> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            four.add(new Attachment("p" + i, "p.png", Attachment.Kind.IMAGE, null, "data:image/png;base64,AAAA", 4));
        }
        assertThrows(ValidationException.class, () -> chat.send(user, null, "look", four));
    }

    @Test
    void chatsBelongToTheirOwner() {
        long owner = TestDb.newUser().getId();
        long other = TestDb.newUser().getId();
        long id = chat.send(owner, null, "private", List.of()).conversation().getId();
        assertEquals(404, assertThrows(ValidationException.class, () -> chat.history(other, id)).getStatus());
        assertEquals(404, assertThrows(ValidationException.class, () -> chat.update(other, id, "mine now", null)).getStatus());
        assertEquals(404, assertThrows(ValidationException.class, () -> chat.deleteConversation(other, id)).getStatus());
    }

    @Test
    void pinnedChatsSitAtTheTopAndStayThereWhenOthersGetNewMessages() {
        long user = TestDb.newUser().getId();
        long first = chat.send(user, null, "first", List.of()).conversation().getId();
        long second = chat.send(user, null, "second", List.of()).conversation().getId();
        chat.update(user, first, null, true);
        chat.send(user, second, "more", List.of());
        List<Conversation> list = chat.listConversations(user);
        assertEquals(first, list.get(0).getId());
        assertTrue(list.get(0).isPinned());
        chat.update(user, first, null, false);
        assertFalse(chat.listConversations(user).get(0).isPinned());
    }

    @Test
    void renamingTrimsLongTitlesAndRejectsBlankOnes() {
        long user = TestDb.newUser().getId();
        long id = chat.send(user, null, "hi", List.of()).conversation().getId();
        assertEquals(60, chat.update(user, id, "x".repeat(100), null).getTitle().length());
        assertThrows(ValidationException.class, () -> chat.update(user, id, "  ", null));
    }

    @Test
    void deleteAllOnlyTouchesTheCallersChats() {
        long a = TestDb.newUser().getId();
        long b = TestDb.newUser().getId();
        chat.send(a, null, "one", List.of());
        chat.send(a, null, "two", List.of());
        chat.send(b, null, "three", List.of());
        assertEquals(2, chat.deleteAllConversations(a));
        assertTrue(chat.listConversations(a).isEmpty());
        assertEquals(1, chat.listConversations(b).size());
    }

    @Test
    void regenerateReplacesTheLatestReply() {
        long user = TestDb.newUser().getId();
        ChatService.ChatResult first = chat.send(user, null, "hi", List.of());
        long id = first.conversation().getId();
        ChatService.ChatResult again = chat.regenerate(user, id);
        List<Message> history = chat.history(user, id);
        assertEquals(2, history.size());
        assertEquals(again.reply().getId(), history.get(1).getId());
    }

    @Test
    void streamingDeliversStartAndTextThenSavesTheReply() {
        long user = TestDb.newUser().getId();
        List<String> events = new ArrayList<>();
        ChatService.ChatResult r = chat.sendStreaming(user, null, "stream me", List.of(), new ChatService.StreamListener() {
            @Override public void onStart(Conversation c, Message m) { events.add("start:" + m.getContent()); }
            @Override public void onDelta(String text) { events.add("delta"); }
        });
        assertEquals("start:stream me", events.get(0));
        assertTrue(events.contains("delta"));
        assertEquals(2, chat.history(user, r.conversation().getId()).size());
    }

    @Test
    void aStreamThatFailsBeforeAnyTextLeavesNoTrace() {
        long user = TestDb.newUser().getId();
        ChatService.StreamListener leaves = new ChatService.StreamListener() {
            @Override public void onStart(Conversation c, Message m) { throw new com.saksham.wanda.exception.ClientGoneException(null); }
            @Override public void onDelta(String text) { }
        };
        assertThrows(com.saksham.wanda.exception.ClientGoneException.class, () -> chat.sendStreaming(user, null, "gone", List.of(), leaves));
        assertTrue(chat.listConversations(user).isEmpty());
    }

    @Test
    void temporaryChatsAreNeverSaved() {
        long user = TestDb.newUser().getId();
        String id = UUID.randomUUID().toString();
        List<String> pieces = new ArrayList<>();
        Message reply = chat.tempStreaming(user, id, "TEMPSECRET", List.of(), false, () -> pieces.add("start"), pieces::add);
        assertTrue(reply.getContent().contains("almost ready"));
        assertTrue(chat.listConversations(user).isEmpty());
        assertEquals("start", pieces.get(0));
        // regenerating works, and so does forgetting the chat
        chat.tempStreaming(user, id, null, List.of(), true, () -> { }, p -> { });
        chat.discardTemp(user, id);
        assertThrows(ValidationException.class, () -> chat.tempStreaming(user, id, null, List.of(), true, () -> { }, p -> { }));
    }

    @Test
    void temporaryChatIdsAreChecked() {
        long user = TestDb.newUser().getId();
        assertThrows(ValidationException.class, () -> chat.tempStreaming(user, "bad id!", "x", List.of(), false, () -> { }, p -> { }));
        assertTrue(ChatService.validTempId(UUID.randomUUID().toString()));
        assertFalse(ChatService.validTempId("short"));
    }

    @Test
    void imageCreationNeedsCloudflareKeys() {
        long user = TestDb.newUser().getId();
        ValidationException e = assertThrows(ValidationException.class, () -> chat.createImage(user, null, "a red fox"));
        assertEquals(503, e.getStatus());
        settings.update(user, Map.of("imageAccountId", "abcdef0123456789", "imageToken", "my_cloudflare_token_000000"));
        assertThrows(ValidationException.class, () -> chat.createImage(user, null, "   "));
    }

    @Test
    void titlesAreOneLineAndTrimmed() {
        assertEquals("a b c", ChatService.titleFrom("a\n b\t c"));
        assertEquals(60, ChatService.titleFrom("y".repeat(200)).length());
    }
}
