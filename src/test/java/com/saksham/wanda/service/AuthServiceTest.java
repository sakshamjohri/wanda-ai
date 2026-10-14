package com.saksham.wanda.service;

import com.saksham.wanda.TestDb;
import com.saksham.wanda.db.UserDao;
import com.saksham.wanda.exception.AuthException;
import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private final UserDao users = new UserDao(TestDb.db());
    private final AuthService auth = new AuthService(users);

    private static String name(String prefix) {
        return prefix + System.nanoTime();
    }

    @Test
    void registersAndSignsInWithAHashedPassword() {
        String username = name("alice");
        User created = auth.register(username, "Alice", "secret1");
        assertTrue(created.getPasswordHash().contains(":"));
        assertNotEquals("secret1", created.getPasswordHash());
        assertEquals(created.getId(), auth.login(username.toUpperCase(), "secret1").getId());   // case-insensitive username
    }

    @Test
    void validatesUsernamesAndPasswords() {
        assertThrows(ValidationException.class, () -> auth.register("ab", "x", "secret1"));
        assertThrows(ValidationException.class, () -> auth.register("has space", "x", "secret1"));
        assertThrows(ValidationException.class, () -> auth.register(name("bob"), "x", "123"));
    }

    @Test
    void duplicateUsernamesAreRefusedWhateverTheCase() {
        String username = name("carol");
        auth.register(username, "Carol", "secret1");
        assertEquals(409, assertThrows(ValidationException.class, () -> auth.register(username.toUpperCase(), "x", "secret1")).getStatus());
    }

    @Test
    void wrongPasswordAndUnknownUserAreRejected() {
        String username = name("dave");
        auth.register(username, "Dave", "secret1");
        assertThrows(AuthException.class, () -> auth.login(username, "wrong-password"));
        assertThrows(AuthException.class, () -> auth.login(name("nobody"), "secret1"));
    }

    @Test
    void anUnusableStoredHashIsRejectedNotACrash() {
        String username = name("erin");
        users.save(new User(username, "Erin", "not-a-salted-hash"));
        assertThrows(AuthException.class, () -> auth.login(username, "secret1"));
    }
}
