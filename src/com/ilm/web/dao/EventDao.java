package com.ilm.web.dao;

import com.ilm.core.model.LoginEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class EventDao {
    public static final String EVENT_COLUMNS = "id, source_type, app_code, user_id, display_name, event_time, event_type, auth_result, "
            + "ip_address, ip_class, pc_name, endpoint, code_location, status";

    private final Db db;

    public EventDao(Db db) { this.db = db; }

    public long insertBatch(Connection c, String origin, String kind, String app, String file, String sha, String by) throws SQLException {
        return Db.insertReturningId(c, "INSERT INTO upload_batch (origin, source_kind, app_code, file_name, file_sha256, uploaded_by) "
                + "VALUES (?,?,?,?,?,?) RETURNING id", origin, kind, app, file, sha, by);
    }

    /** Returns the new id, or null when this exact record was already stored before. */
    public Long insertEvent(Connection c, long batchId, LoginEvent e) throws SQLException {
        return Db.insertReturningId(c, "INSERT INTO login_event (event_hash, batch_id, source_type, app_code, user_id, display_name, event_time, "
                + "event_type, auth_result, ip_address, ip_class, pc_name, endpoint, thread_name, code_location, source_ref, raw_text) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (event_hash) DO NOTHING RETURNING id",
                e.eventHash, batchId, e.sourceType, e.appCode, e.userId, e.displayName, e.eventTime, e.eventType, e.result,
                e.ipAddress, e.ipClass, e.pcName, e.endpoint, e.threadName, e.codeLocation, e.sourceRef, e.rawText);
    }

    public void insertError(Connection c, long batchId, int lineNo, String raw, String message) throws SQLException {
        Db.update(c, "INSERT INTO event_error (batch_id, line_no, raw_text, message) VALUES (?,?,?,?)", batchId, lineNo, raw, message);
    }

    public void finishBatch(Connection c, long id, int accepted, int duplicates, int rejected, int skipped) throws SQLException {
        Db.update(c, "UPDATE upload_batch SET accepted=?, duplicates=?, rejected=?, skipped=? WHERE id=?", accepted, duplicates, rejected, skipped, id);
    }

    public List<Map<String, Object>> recentBatches(int limit) {
        return db.query("SELECT * FROM upload_batch ORDER BY id DESC LIMIT ?", limit);
    }

    public List<Map<String, Object>> batchErrors(long batchId, int limit) {
        return db.query("SELECT line_no, raw_text, message FROM event_error WHERE batch_id=? ORDER BY id LIMIT ?", batchId, limit);
    }

    public long pendingCount() { return db.count("SELECT count(*) FROM login_event WHERE status='NORMALIZED'"); }

    public long loginCount() { return db.count("SELECT count(*) FROM login_event WHERE event_type='LOGIN'"); }

    public LocalDateTime latestEventTime() {
        Map<String, Object> r = db.one("SELECT max(event_time) AS t FROM login_event");
        return r == null || r.get("t") == null ? null : ((Timestamp) r.get("t")).toLocalDateTime();
    }

    public static LoginEvent map(ResultSet rs) throws SQLException {
        LoginEvent e = new LoginEvent();
        e.id = rs.getLong("id");
        e.sourceType = rs.getString("source_type");
        e.appCode = rs.getString("app_code");
        e.userId = rs.getString("user_id");
        e.displayName = rs.getString("display_name");
        e.eventTime = rs.getTimestamp("event_time").toLocalDateTime();
        e.eventType = rs.getString("event_type");
        e.result = rs.getString("auth_result");
        e.ipAddress = rs.getString("ip_address");
        e.ipClass = rs.getString("ip_class");
        e.pcName = rs.getString("pc_name");
        e.endpoint = rs.getString("endpoint");
        e.codeLocation = rs.getString("code_location");
        return e;
    }

    /** Filter values come from the request; they are bound as parameters, never concatenated. */
    public record Filter(String user, String app, String level, String result, String type, LocalDateTime from, LocalDateTime to) {}

    private static String where(Filter f, List<Object> p) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (f.user() != null) { w.append(" AND e.user_id LIKE ?"); p.add("%" + f.user().toLowerCase().replace("%", "").replace("_", "\\_") + "%"); }
        if (f.app() != null) { w.append(" AND e.app_code = ?"); p.add(f.app()); }
        if (f.level() != null) { w.append(" AND r.risk_level = ?"); p.add(f.level()); }
        if (f.result() != null) { w.append(" AND e.auth_result = ?"); p.add(f.result()); }
        if (f.type() != null) { w.append(" AND e.event_type = ?"); p.add(f.type()); }
        if (f.from() != null) { w.append(" AND e.event_time >= ?"); p.add(f.from()); }
        if (f.to() != null) { w.append(" AND e.event_time < ?"); p.add(f.to()); }
        return w.toString();
    }

    private static final String JOINED = " FROM login_event e LEFT JOIN risk_assessment r ON r.event_id = e.id "
            + "LEFT JOIN alert_event ae ON ae.event_id = e.id";

    public long searchCount(Filter f) {
        List<Object> p = new ArrayList<>();
        String w = where(f, p);
        return db.count("SELECT count(*)" + JOINED + w, p.toArray());
    }

    public List<Map<String, Object>> search(Filter f, int limit, int offset) {
        List<Object> p = new ArrayList<>();
        String w = where(f, p);
        p.add(limit);
        p.add(offset);
        return db.query("SELECT e.id, e.event_time, e.user_id, e.display_name, e.app_code, e.source_type, e.event_type, e.auth_result, "
                + "e.ip_address, e.ip_class, r.risk_score, r.risk_level, r.anomaly_score, ae.alert_id" + JOINED + w
                + " ORDER BY e.event_time DESC, e.id DESC LIMIT ? OFFSET ?", p.toArray());
    }

    public Map<String, Object> detail(long id) {
        return db.one("SELECT e.*, r.risk_score, r.risk_level, r.anomaly_score, r.ml_points, r.rule_points, r.factors, r.model_version, "
                + "r.assessed_at, ae.alert_id" + JOINED + " WHERE e.id = ?", id);
    }

    public List<Map<String, Object>> userRecent(String userId, LocalDateTime before, int limit) {
        return db.query("SELECT e.id, e.event_time, e.app_code, e.source_type, e.event_type, e.auth_result, e.ip_address, r.risk_score, r.risk_level"
                + " FROM login_event e LEFT JOIN risk_assessment r ON r.event_id = e.id WHERE e.user_id = ? AND e.event_time <= ?"
                + " ORDER BY e.event_time DESC, e.id DESC LIMIT ?", userId, before, limit);
    }

    public List<Map<String, Object>> appCodes() {
        return db.query("SELECT DISTINCT app_code FROM login_event ORDER BY 1");
    }
}
