package com.saksham.wanda.db;

import com.saksham.wanda.model.User;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Database operations for the {@code users} table. */
public class UserDao extends BaseDao<User> implements Dao<User> {

    public UserDao(Database db) {
        super(db);
    }

    @Override
    protected User map(ResultSet rs) throws SQLException {
        User u = new User(rs.getString("username"), rs.getString("display_name"), rs.getString("password_hash"));
        u.setId(rs.getLong("id"));
        u.setCreatedAt(rs.getLong("created_at"));
        return u;
    }

    @Override
    public Optional<User> findById(long id) {
        return queryOne("SELECT * FROM users WHERE id = ?", id);
    }

    public Optional<User> findByUsername(String username) {
        return queryOne("SELECT * FROM users WHERE LOWER(username) = LOWER(?)", username);
    }

    /** Users are top-level records, so "owner" is not meaningful here. */
    @Override
    public List<User> findByOwner(long ownerId) {
        return findById(ownerId).map(List::of).orElse(List.of());
    }

    @Override
    public User save(User u) {
        u.setId(insert("INSERT INTO users (username, display_name, password_hash, created_at) VALUES (?, ?, ?, ?)",
                u.getUsername(), u.getDisplayName(), u.getPasswordHash(), u.getCreatedAt()));
        return u;
    }

    public void updateDisplayName(long id, String displayName) {
        update("UPDATE users SET display_name = ? WHERE id = ?", displayName, id);
    }

    @Override
    public boolean delete(long id) {
        return update("DELETE FROM users WHERE id = ?", id) > 0;
    }
}
