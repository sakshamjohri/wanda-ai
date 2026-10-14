package com.saksham.wanda.db;

import com.saksham.wanda.exception.DataAccessException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the JDBC URL and creates the schema on first start.
 * Thread-safe singleton: {@link #init} is synchronized and the instance is volatile.
 *
 * @author Saksham Johri
 */
public final class Database {

    private static volatile Database instance;
    private final String url;

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS users (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            username      VARCHAR(120) NOT NULL UNIQUE,
            display_name  VARCHAR(60)  NOT NULL,
            password_hash VARCHAR(200) NOT NULL,
            created_at    BIGINT       NOT NULL)""",
        // Databases created by older versions had a shorter username column.
        "ALTER TABLE users ALTER COLUMN username SET DATA TYPE VARCHAR(120)",
        """
        CREATE TABLE IF NOT EXISTS conversations (
            id         BIGINT AUTO_INCREMENT PRIMARY KEY,
            user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            title      VARCHAR(120) NOT NULL,
            created_at BIGINT       NOT NULL,
            updated_at BIGINT       NOT NULL)""",
        """
        CREATE TABLE IF NOT EXISTS messages (
            id              BIGINT AUTO_INCREMENT PRIMARY KEY,
            conversation_id BIGINT      NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
            role            VARCHAR(16) NOT NULL,
            content         CLOB        NOT NULL,
            attachments     VARCHAR(1000),
            context         CLOB,
            created_at      BIGINT      NOT NULL)""",
        """
        CREATE TABLE IF NOT EXISTS settings (
            user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            setting_key   VARCHAR(64)  NOT NULL,
            setting_value VARCHAR(8000),
            PRIMARY KEY (user_id, setting_key))""",
        // Added after the first release; IF NOT EXISTS makes them safe on every start.
        "ALTER TABLE conversations ADD COLUMN IF NOT EXISTS pinned BOOLEAN DEFAULT FALSE NOT NULL",
        "ALTER TABLE messages ADD COLUMN IF NOT EXISTS images CLOB",
        "ALTER TABLE messages ADD COLUMN IF NOT EXISTS image_count INT DEFAULT 0 NOT NULL",
        "ALTER TABLE messages ADD COLUMN IF NOT EXISTS kind VARCHAR(8) DEFAULT 'text' NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id, updated_at)"
    };

    private Database(String url) {
        this.url = url;
    }

    /** Creates the singleton and the tables. Safe to call more than once. */
    public static synchronized Database init(String url) {
        if (instance == null) {
            Database db = new Database(url);
            db.createSchema();
            instance = db;
        }
        return instance;
    }

    public static Database get() {
        if (instance == null) {
            throw new IllegalStateException("Database.init() has not been called");
        }
        return instance;
    }

    /** Opens a new JDBC connection. Callers close it with try-with-resources. */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    private void createSchema() {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            for (String ddl : SCHEMA) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not create database schema", e);
        }
    }
}
