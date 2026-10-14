package com.saksham.wanda.service;

import com.saksham.wanda.model.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TempChatStoreTest {

    @Test
    void sameIdGivesTheSameChatAndDifferentUsersAreSeparate() {
        TempChatStore store = new TempChatStore();
        TempChatStore.TempChat a = store.open(1, "chat-0001");
        assertSame(a, store.open(1, "chat-0001"));
        assertNotSame(a, store.open(2, "chat-0001"));
    }

    @Test
    void keepsMessagesInOrderAndCapsTheirNumber() {
        TempChatStore.TempChat chat = new TempChatStore().open(1, "chat-0001");
        for (int i = 0; i < 60; i++) {
            chat.add(new Message(0, Message.Role.USER, "m" + i));
        }
        assertEquals(40, chat.snapshot().size());
        assertEquals("m20", chat.snapshot().get(0).getContent());
        assertEquals("m59", chat.last().getContent());
    }

    @Test
    void discardForgetsEverything() {
        TempChatStore store = new TempChatStore();
        store.open(1, "chat-0001").add(new Message(0, Message.Role.USER, "secret"));
        store.discard(1, "chat-0001");
        assertNull(store.open(1, "chat-0001").last());
    }

    @Test
    void removeLastReturnsTheNewestMessage() {
        TempChatStore.TempChat chat = new TempChatStore().open(1, "chat-0001");
        chat.add(new Message(0, Message.Role.USER, "q"));
        chat.add(new Message(0, Message.Role.ASSISTANT, "a"));
        assertEquals("a", chat.removeLast().getContent());
        assertEquals("q", chat.last().getContent());
    }
}
