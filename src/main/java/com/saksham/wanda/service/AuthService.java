package com.saksham.wanda.service;

import com.saksham.wanda.db.UserDao;
import com.saksham.wanda.exception.AuthException;
import com.saksham.wanda.exception.DataAccessException;
import com.saksham.wanda.exception.ValidationException;
import com.saksham.wanda.model.User;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Account registration and login. Passwords are stored as salted
 * PBKDF2-HMAC-SHA256 hashes in the form {@code salt:hash} (both Base64).
 *
 * @author Saksham Johri
 */
public class AuthService {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDao users;

    public AuthService(UserDao users) {
        this.users = users;
    }

    public User register(String username, String displayName, String password) {
        username = username == null ? "" : username.trim();
        if (!username.matches("[A-Za-z0-9_.@+-]{3,100}")) {
            throw new ValidationException("Use 3-100 characters for your username: letters, numbers or an email address");
        }
        if (password == null || password.length() < 6) {
            throw new ValidationException("Password must be at least 6 characters");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new ValidationException(409, "That username is already taken");
        }
        String name = displayName == null || displayName.isBlank() ? username : displayName.trim();
        try {
            return users.save(new User(username, shorten(name), hash(password)));
        } catch (DataAccessException e) {
            // Two sign-ups racing for the same name: the database's unique constraint stops the second.
            if (users.findByUsername(username).isPresent()) {
                throw new ValidationException(409, "That username is already taken");
            }
            throw e;
        }
    }

    public User login(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new ValidationException("Enter your username and password");
        }
        User user = users.findByUsername(name).orElseThrow(() ->
                new AuthException("No account found for \"" + name + "\". Choose Create account to make one."));
        if (!verify(password, user.getPasswordHash())) {
            throw new AuthException("Incorrect password. Please try again.");
        }
        return user;
    }

    private static String shorten(String name) {
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    public User findUser(long id) {
        return users.findById(id).orElseThrow(() -> new AuthException("Account not found"));
    }

    public void updateDisplayName(long id, String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            users.updateDisplayName(id, displayName.trim().substring(0, Math.min(60, displayName.trim().length())));
        }
    }

    private static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return b64(salt) + ":" + b64(pbkdf2(password, salt));
    }

    private static boolean verify(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) {
            return false; // not a salt:hash value, so no password can match it
        }
        try {
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            byte[] actual = pbkdf2(password, Base64.getDecoder().decode(parts[0]));
            return MessageDigest.isEqual(expected, actual); // constant-time comparison
        } catch (IllegalArgumentException e) {
            return false; // stored value is not valid Base64
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available on this JVM", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
