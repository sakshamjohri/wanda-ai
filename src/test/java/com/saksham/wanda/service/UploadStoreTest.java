package com.saksham.wanda.service;

import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.Attachment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadStoreTest {

    private static Attachment text(String id) {
        return new Attachment(id, id + ".txt", Attachment.Kind.TEXT, "hello", null, 5);
    }

    private static Attachment picture(String id) {
        return new Attachment(id, id + ".png", Attachment.Kind.IMAGE, null, "data:image/png;base64,AAAA", 4);
    }

    @Test
    void resolvesOnlyTheOwnersFiles() {
        UploadStore store = new UploadStore();
        store.put(1, text("a"));
        assertEquals(List.of("a"), store.resolve(1, List.of("a")).stream().map(Attachment::id).toList());
        assertThrows(ValidationException.class, () -> store.resolve(2, List.of("a")));
        assertThrows(ValidationException.class, () -> store.resolve(1, List.of("missing")));
    }

    @Test
    void removedFilesAreGone() {
        UploadStore store = new UploadStore();
        store.put(1, text("a"));
        assertTrue(store.remove(1, "a"));
        assertFalse(store.remove(1, "a"));
        assertThrows(ValidationException.class, () -> store.resolve(1, List.of("a")));
    }

    @Test
    void allowsAtMostThreePicturesAtOnce() {
        UploadStore store = new UploadStore();
        store.put(1, picture("p1"));
        store.put(1, picture("p2"));
        store.put(1, picture("p3"));
        ValidationException e = assertThrows(ValidationException.class, () -> store.put(1, picture("p4")));
        assertEquals(400, e.getStatus());
        store.put(1, text("t")); // other files are not limited by the picture rule
    }
}
