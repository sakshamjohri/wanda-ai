package com.saksham.wanda.db;

import com.saksham.wanda.exception.DataAccessException;
import com.saksham.wanda.model.Message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Database operations for the {@code messages} table. */
public class MessageDao extends BaseDao<Message> implements Dao<Message> {

    /** Every column except the (potentially large) pictures, which are loaded only when needed. */
    private static final String LIGHT_COLUMNS =
            "id, conversation_id, role, content, attachments, context, image_count, kind, created_at";

    public MessageDao(Database db) {
        super(db);
    }

    @Override
    protected Message map(ResultSet rs) throws SQLException {
        Message m = new Message(rs.getLong("conversation_id"),
                Message.Role.valueOf(rs.getString("role")), rs.getString("content"));
        m.setId(rs.getLong("id"));
        m.setAttachments(rs.getString("attachments"));
        m.setContext(rs.getString("context"));
        m.setImageCount(rs.getInt("image_count"));
        m.setKind(rs.getString("kind"));
        m.setCreatedAt(rs.getLong("created_at"));
        return m;
    }

    @Override
    public Optional<Message> findById(long id) {
        return queryOne("SELECT " + LIGHT_COLUMNS + " FROM messages WHERE id = ?", id);
    }

    /** All messages of a conversation in chronological order. */
    @Override
    public List<Message> findByOwner(long conversationId) {
        return query("SELECT " + LIGHT_COLUMNS + " FROM messages WHERE conversation_id = ? ORDER BY id", conversationId);
    }

    /** The last {@code limit} messages, oldest first: the context window sent to the model. */
    public List<Message> findRecent(long conversationId, int limit) {
        List<Message> rows = query("SELECT " + LIGHT_COLUMNS
                + " FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?", conversationId, limit);
        Collections.reverse(rows);
        return rows;
    }

    /** The stored pictures of one message (data URLs separated by newlines), or null. */
    public String loadImages(long messageId) {
        try (var con = db.getConnection();
             var ps = con.prepareStatement("SELECT images FROM messages WHERE id = ?")) {
            ps.setLong(1, messageId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load pictures", e);
        }
    }

    /** The picture a user's assistant made (data URL), or null if the message is not theirs or has none. */
    public String loadCreatedImage(long messageId, long userId) {
        try (var con = db.getConnection();
             var ps = con.prepareStatement("SELECT m.images FROM messages m JOIN conversations c ON c.id = m.conversation_id "
                     + "WHERE m.id = ? AND c.user_id = ? AND m.kind = 'image' AND m.role = 'ASSISTANT'")) {
            ps.setLong(1, messageId);
            ps.setLong(2, userId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load the image", e);
        }
    }

    @Override
    public Message save(Message m) {
        m.setId(insert("INSERT INTO messages (conversation_id, role, content, attachments, context, images, image_count, kind, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                m.getConversationId(), m.getRole().name(), m.getContent(),
                m.getAttachments(), m.getContext(), m.getImages(), m.getImageCount(), m.getKind(), m.getCreatedAt()));
        return m;
    }

    @Override
    public boolean delete(long id) {
        return update("DELETE FROM messages WHERE id = ?", id) > 0;
    }
}
