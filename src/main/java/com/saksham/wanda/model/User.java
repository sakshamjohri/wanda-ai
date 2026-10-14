package com.saksham.wanda.model;

/** A registered account. The password hash is transient so it never reaches JSON output. */
public class User extends BaseEntity {

    private String username;
    private String displayName;
    private transient String passwordHash;

    public User() { }

    public User(String username, String displayName, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    @Override
    public String label() { return username; }
}
