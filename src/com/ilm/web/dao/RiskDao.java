package com.ilm.web.dao;

import com.ilm.core.detect.DetectionState.UserHist;
import com.ilm.core.detect.RiskResult;
import com.ilm.core.model.LoginEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class RiskDao {
    private final Db db;

    public RiskDao(Db db) { this.db = db; }

    public void saveRisk(Connection c, long eventId, RiskResult r, Integer modelVersion) throws SQLException {
        Db.update(c, "INSERT INTO risk_assessment (event_id, anomaly_score, ml_points, rule_points, risk_score, risk_level, factors, model_version) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO UPDATE SET anomaly_score=EXCLUDED.anomaly_score, ml_points=EXCLUDED.ml_points, "
                + "rule_points=EXCLUDED.rule_points, risk_score=EXCLUDED.risk_score, risk_level=EXCLUDED.risk_level, factors=EXCLUDED.factors, "
                + "model_version=EXCLUDED.model_version, assessed_at=now()",
                eventId, r.anomalyScore, r.mlPoints, r.rulePoints, r.riskScore, r.riskLevel, r.factorsText(), modelVersion);
    }

    public void markScored(Connection c, long eventId) throws SQLException {
        Db.update(c, "UPDATE login_event SET status='SCORED' WHERE id=? AND status='NORMALIZED'", eventId);
    }

    public boolean hasAlert(Connection c, long eventId) throws SQLException {
        return !Db.query(c, "SELECT 1 FROM alert_event WHERE event_id=?", eventId).isEmpty();
    }

    /**
     * Attach the event to an open alert of the same user and application within 30 minutes,
     * or create a new alert. Returns the alert id when a NEW alert was created, else null.
     */
    public Long attachOrCreateAlert(Connection c, LoginEvent e, RiskResult r) throws SQLException {
        String top = r.factors.isEmpty() ? null : r.factors.get(0).text();
        if (top != null && top.length() > 500) top = top.substring(0, 500);
        LocalDateTime t = e.eventTime;
        List<Map<String, Object>> open = Db.query(c, "SELECT id, max_risk_score FROM alert WHERE user_id=? AND app_code=? AND status<>'CLOSED' "
                + "AND last_event_time >= ? AND first_event_time <= ? ORDER BY id DESC LIMIT 1", e.userId, e.appCode, t.minusMinutes(30), t.plusMinutes(30));
        if (!open.isEmpty()) {
            long alertId = ((Number) open.get(0).get("id")).longValue();
            int max = Math.max(((Number) open.get(0).get("max_risk_score")).intValue(), r.riskScore);
            Db.update(c, "UPDATE alert SET event_count = event_count + 1, last_event_time = GREATEST(last_event_time, ?), "
                    + "first_event_time = LEAST(first_event_time, ?), max_risk_score = ?, severity = ?, "
                    + "top_factor = CASE WHEN ? > max_risk_score THEN ?::varchar ELSE top_factor END WHERE id = ?",
                    t, t, max, RiskResult.level(max), r.riskScore, top, alertId);
            Db.update(c, "INSERT INTO alert_event (alert_id, event_id) VALUES (?,?) ON CONFLICT DO NOTHING", alertId, e.id);
            return null;
        }
        Long alertId = Db.insertReturningId(c, "INSERT INTO alert (user_id, app_code, severity, first_event_time, last_event_time, max_risk_score, top_factor) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id", e.userId, e.appCode, r.riskLevel, t, t, r.riskScore, top);
        Db.update(c, "INSERT INTO alert_event (alert_id, event_id) VALUES (?,?)", alertId, e.id);
        return alertId;
    }

    public void upsertProfile(Connection c, String userId, UserHist h) throws SQLException {
        String hours = Arrays.stream(h.hour).mapToObj(String::valueOf).collect(Collectors.joining(","));
        String dows = Arrays.stream(h.dow).mapToObj(String::valueOf).collect(Collectors.joining(","));
        String apps = h.apps.stream().sorted().collect(Collectors.joining(","));
        if (apps.length() > 1000) apps = apps.substring(0, 1000);
        Db.update(c, "INSERT INTO user_profile (user_id, first_seen, last_seen, total_logins, failed_logins, active_days, hour_hist, dow_hist, apps) "
                + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (user_id) DO UPDATE SET first_seen=EXCLUDED.first_seen, last_seen=EXCLUDED.last_seen, "
                + "total_logins=EXCLUDED.total_logins, failed_logins=EXCLUDED.failed_logins, active_days=EXCLUDED.active_days, "
                + "hour_hist=EXCLUDED.hour_hist, dow_hist=EXCLUDED.dow_hist, apps=EXCLUDED.apps, updated_at=now()",
                userId, h.firstSeen, h.lastSeen, h.total, h.failed, h.activeDays, hours, dows, apps);
    }

    public Map<String, Object> profile(String userId) {
        return db.one("SELECT * FROM user_profile WHERE user_id=?", userId);
    }

    // ---------- alerts and investigation ----------

    public List<Map<String, Object>> alerts(String status, String severity, int limit, int offset) {
        return db.query("SELECT a.*, i.analyst, i.status AS inv_status FROM alert a LEFT JOIN investigation i ON i.alert_id = a.id "
                + "WHERE (?::varchar IS NULL OR a.status = ?) AND (?::varchar IS NULL OR a.severity = ?) "
                + "ORDER BY CASE a.status WHEN 'OPEN' THEN 0 WHEN 'IN_PROGRESS' THEN 1 ELSE 2 END, a.max_risk_score DESC, a.last_event_time DESC "
                + "LIMIT ? OFFSET ?", status, status, severity, severity, limit, offset);
    }

    public long alertCount(String status, String severity) {
        return db.count("SELECT count(*) FROM alert a WHERE (?::varchar IS NULL OR a.status = ?) AND (?::varchar IS NULL OR a.severity = ?)",
                status, status, severity, severity);
    }

    public Map<String, Object> alert(long id) {
        return db.one("SELECT a.*, i.analyst, i.status AS inv_status, i.result AS inv_result, i.created_at AS inv_started "
                + "FROM alert a LEFT JOIN investigation i ON i.alert_id = a.id WHERE a.id = ?", id);
    }

    public List<Map<String, Object>> alertEvents(long alertId) {
        return db.query("SELECT e.id, e.event_time, e.user_id, e.app_code, e.source_type, e.event_type, e.auth_result, e.ip_address, e.ip_class, "
                + "r.risk_score, r.risk_level, r.anomaly_score, r.factors FROM alert_event ae JOIN login_event e ON e.id = ae.event_id "
                + "LEFT JOIN risk_assessment r ON r.event_id = e.id WHERE ae.alert_id = ? ORDER BY e.event_time, e.id LIMIT 200", alertId);
    }

    public List<Map<String, Object>> notes(long alertId) {
        return db.query("SELECT author, note, created_at FROM investigation_note WHERE alert_id=? ORDER BY id", alertId);
    }

    /** OPEN -> IN_PROGRESS. Returns false when the alert was not OPEN any more. */
    public boolean startInvestigation(long alertId, String analyst) {
        return db.tx(c -> {
            if (Db.update(c, "UPDATE alert SET status='IN_PROGRESS' WHERE id=? AND status='OPEN'", alertId) == 0) return false;
            Db.update(c, "INSERT INTO investigation (alert_id, analyst) VALUES (?,?) ON CONFLICT (alert_id) DO NOTHING", alertId, analyst);
            return true;
        });
    }

    public void addNote(long alertId, String author, String note) {
        db.update("INSERT INTO investigation_note (alert_id, author, note) VALUES (?,?,?)", alertId, author, note);
    }

    public boolean escalate(long alertId) {
        return db.update("UPDATE investigation SET status='ESCALATED' WHERE alert_id=? AND status='UNDER_INVESTIGATION'", alertId) > 0;
    }

    /** Any open state -> CLOSED with a resolution. Returns false when it was already closed. */
    public boolean close(long alertId, String resolution, String by) {
        return db.tx(c -> {
            if (Db.update(c, "UPDATE alert SET status='CLOSED', resolution=?, closed_by=?, closed_at=now() WHERE id=? AND status<>'CLOSED'",
                    resolution, by, alertId) == 0) return false;
            if ("FALSE_POSITIVE".equals(resolution) || "CONFIRMED_RISK".equals(resolution)) {
                Db.update(c, "UPDATE investigation SET status='CLOSED', result=?, closed_at=now() WHERE alert_id=?", resolution, alertId);
            }
            return true;
        });
    }

    public void markEmailSent(long alertId) {
        db.update("UPDATE alert SET email_sent=true WHERE id=?", alertId);
    }
}
