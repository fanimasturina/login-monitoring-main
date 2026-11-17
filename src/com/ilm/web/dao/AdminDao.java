package com.ilm.web.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Application users, settings, audit trail, model versions and real-time checkpoints. */
@Repository
public class AdminDao {
    private final Db db;

    public AdminDao(Db db) { this.db = db; }

    // ---------- users (UC-12) ----------
    public Map<String, Object> user(String userId) { return db.one("SELECT * FROM app_user WHERE user_id=?", userId); }

    public List<Map<String, Object>> users() {
        return db.query("SELECT user_id, display_name, email, role, auth_source, active, created_at, created_by, last_login_at FROM app_user ORDER BY user_id");
    }

    public void saveUser(String userId, String displayName, String email, String role, String source, String hash, boolean active, String by) {
        db.update("INSERT INTO app_user (user_id, display_name, email, role, auth_source, password_hash, active, created_by) VALUES (?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (user_id) DO UPDATE SET display_name=COALESCE(EXCLUDED.display_name, app_user.display_name), "
                + "email=COALESCE(EXCLUDED.email, app_user.email), role=EXCLUDED.role, active=EXCLUDED.active, "
                + "password_hash=COALESCE(EXCLUDED.password_hash, app_user.password_hash)",
                userId, displayName, email, role, source, hash, active, by);
    }

    public void touchLogin(String userId, String displayName, String email) {
        db.update("UPDATE app_user SET last_login_at=now(), display_name=COALESCE(?, display_name), email=COALESCE(?, email) WHERE user_id=?",
                displayName, email, userId);
    }

    public long activeAdmins() { return db.count("SELECT count(*) FROM app_user WHERE role='ADMIN' AND active"); }

    public List<String> emailsOf(String role) {
        return db.query("SELECT email FROM app_user WHERE role=? AND active AND email IS NOT NULL AND email<>''", role)
                .stream().map(r -> String.valueOf(r.get("email"))).toList();
    }

    // ---------- settings ----------
    public Map<String, String> settings() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.query("SELECT key, value FROM app_setting")) out.put((String) r.get("key"), (String) r.get("value"));
        return out;
    }

    public void saveSetting(String key, String value, String by) {
        db.update("INSERT INTO app_setting (key, value, updated_by) VALUES (?,?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, "
                + "updated_by=EXCLUDED.updated_by, updated_at=now()", key, value, by);
    }

    // ---------- audit trail ----------
    public void audit(String actor, String action, String target, String detail, String ip) {
        db.update("INSERT INTO audit_trail (actor, action, target, detail, ip) VALUES (?,?,?,?,?)", actor, action, target, detail, ip);
    }

    public List<Map<String, Object>> auditRows(String actor, String action, int limit, int offset) {
        return db.query("SELECT * FROM audit_trail WHERE (?::varchar IS NULL OR actor = ?) AND (?::varchar IS NULL OR action = ?) "
                + "ORDER BY id DESC LIMIT ? OFFSET ?", actor, actor, action, action, limit, offset);
    }

    public long auditCount(String actor, String action) {
        return db.count("SELECT count(*) FROM audit_trail WHERE (?::varchar IS NULL OR actor = ?) AND (?::varchar IS NULL OR action = ?)",
                actor, actor, action, action);
    }

    // ---------- model versions (NFR-M03) ----------
    public Map<String, Object> activeModel() { return db.one("SELECT * FROM ml_model WHERE active ORDER BY version DESC LIMIT 1"); }

    public List<Map<String, Object>> models() {
        return db.query("SELECT version, model_type, trained_at, trained_by, sample_count, tree_count, sample_size, active FROM ml_model ORDER BY version DESC LIMIT 30");
    }

    public int saveModel(String type, String by, int samples, int trees, int sampleSize, String features, String data) {
        return db.tx(c -> {
            Db.update(c, "UPDATE ml_model SET active=false WHERE active");
            return Db.insertReturningId(c, "INSERT INTO ml_model (model_type, trained_by, sample_count, tree_count, sample_size, feature_names, model_data, active) "
                    + "VALUES (?,?,?,?,?,?,?,true) RETURNING version", type, by, samples, trees, sampleSize, features, data).intValue();
        });
    }

    // ---------- real-time checkpoints ----------
    public long checkpoint(String key) {
        Map<String, Object> r = db.one("SELECT position FROM ingest_checkpoint WHERE source_key=?", key);
        return r == null ? -1 : ((Number) r.get("position")).longValue();
    }

    public void saveCheckpoint(Connection c, String key, long position) throws SQLException {
        Db.update(c, "INSERT INTO ingest_checkpoint (source_key, position) VALUES (?,?) ON CONFLICT (source_key) DO UPDATE SET "
                + "position=EXCLUDED.position, updated_at=now()", key, position);
    }
}
