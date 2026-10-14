package com.saksham.wanda.model;

/** One chat thread owned by a user. */
public class Conversation extends BaseEntity {

    private long userId;
    private String title;
    private long updatedAt = System.currentTimeMillis();
    private boolean pinned;

    public Conversation() { }

    public Conversation(long userId, String title) {
        this.userId = userId;
        this.title = title;
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    @Override
    public String label() { return title; }
}
