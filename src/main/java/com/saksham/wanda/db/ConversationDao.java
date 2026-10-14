package com.saksham.wanda.db;

import com.saksham.wanda.model.Conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Database operations for the {@code conversations} table. */
public class ConversationDao extends BaseDao<Conversation> implements Dao<Conversation> {

    public ConversationDao(Database db) {
        super(db);
    }

    @Override
    protected Conversation map(ResultSet rs) throws SQLException {
        Conversation c = new Conversation(rs.getLong("user_id"), rs.getString("title"));
        c.setId(rs.getLong("id"));
        c.setCreatedAt(rs.getLong("created_at"));
        c.setUpdatedAt(rs.getLong("updated_at"));
        c.setPinned(rs.getBoolean("pinned"));
        return c;
    }

    @Override
    public Optional<Conversation> findById(long id) {
        return queryOne("SELECT * FROM conversations WHERE id = ?", id);
    }

    /** Conversations of a user: pinned ones first, then most recently active first. */
    @Override
    public List<Conversation> findByOwner(long userId) {
        return query("SELECT * FROM conversations WHERE user_id = ? ORDER BY pinned DESC, updated_at DESC", userId);
    }

    @Override
    public Conversation save(Conversation c) {
        c.setId(insert("INSERT INTO conversations (user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                c.getUserId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()));
        return c;
    }

    public void rename(long id, String title) {
        update("UPDATE conversations SET title = ? WHERE id = ?", title, id);
    }

    public void setPinned(long id, boolean pinned) {
        update("UPDATE conversations SET pinned = ? WHERE id = ?", pinned, id);
    }

    public void touch(long id) {
        update("UPDATE conversations SET updated_at = ? WHERE id = ?", System.currentTimeMillis(), id);
    }

    @Override
    public boolean delete(long id) {
        return update("DELETE FROM conversations WHERE id = ?", id) > 0;
    }

    public int deleteAllForUser(long userId) {
        return update("DELETE FROM conversations WHERE user_id = ?", userId);
    }
}
