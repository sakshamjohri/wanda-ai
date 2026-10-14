package com.saksham.wanda.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Key/value settings per user, stored in the {@code settings} table. */
public class SettingsDao extends BaseDao<Map.Entry<String, String>> {

    public SettingsDao(Database db) {
        super(db);
    }

    @Override
    protected Map.Entry<String, String> map(ResultSet rs) throws SQLException {
        return Map.entry(rs.getString("setting_key"), rs.getString("setting_value"));
    }

    public Map<String, String> load(long userId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : query("SELECT * FROM settings WHERE user_id = ?", userId)) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    /** Insert-or-update using H2's MERGE statement. */
    public void put(long userId, String key, String value) {
        update("MERGE INTO settings (user_id, setting_key, setting_value) KEY (user_id, setting_key) VALUES (?, ?, ?)",
                userId, key, value);
    }
}
