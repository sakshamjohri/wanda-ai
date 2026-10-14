package com.saksham.wanda.model;

/**
 * Common parent of every persisted entity: a database id and a creation time.
 * Timestamps are epoch milliseconds so they serialise cleanly to JSON.
 *
 * @author Saksham Johri
 */
public abstract class BaseEntity {

    protected long id;
    protected long createdAt = System.currentTimeMillis();

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    /** Short human-readable label, overridden by each entity (used in logs). */
    public abstract String label();

    @Override
    public String toString() {
        return getClass().getSimpleName() + "#" + id + " (" + label() + ")";
    }
}
