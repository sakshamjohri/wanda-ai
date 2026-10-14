package com.saksham.wanda.db;

import com.saksham.wanda.exception.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC plumbing shared by all DAOs: parameter binding, result mapping and
 * resource handling with try-with-resources. Subclasses only supply SQL and
 * a {@link #map(ResultSet)} implementation.
 *
 * @param <T> type produced from each result row
 * @author Saksham Johri
 */
public abstract class BaseDao<T> {

    protected final Database db;

    protected BaseDao(Database db) {
        this.db = db;
    }

    /** Converts the current row of a ResultSet into an object. */
    protected abstract T map(ResultSet rs) throws SQLException;

    protected List<T> query(String sql, Object... params) {
        try (Connection con = db.getConnection();
             PreparedStatement ps = prepare(con, sql, false, params);
             ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new DataAccessException("Query failed: " + sql, e);
        }
    }

    protected Optional<T> queryOne(String sql, Object... params) {
        List<T> rows = query(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Runs INSERT/UPDATE/DELETE and returns the number of affected rows. */
    protected int update(String sql, Object... params) {
        try (Connection con = db.getConnection();
             PreparedStatement ps = prepare(con, sql, false, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Update failed: " + sql, e);
        }
    }

    /** Runs an INSERT and returns the auto-generated primary key. */
    protected long insert(String sql, Object... params) {
        try (Connection con = db.getConnection();
             PreparedStatement ps = prepare(con, sql, true, params)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("No generated key returned");
            }
        } catch (SQLException e) {
            throw new DataAccessException("Insert failed: " + sql, e);
        }
    }

    private PreparedStatement prepare(Connection con, String sql, boolean keys, Object... params)
            throws SQLException {
        PreparedStatement ps = keys
                ? con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                : con.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }
}
