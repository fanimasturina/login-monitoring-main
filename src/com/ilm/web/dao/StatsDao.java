package com.ilm.web.dao;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Aggregates for the dashboard and reports (FR-33..FR-39). */
@Repository
public class StatsDao {
    private static final String BASE = " FROM login_event e LEFT JOIN risk_assessment r ON r.event_id = e.id WHERE e.event_time >= ? AND e.event_time < ?";
    private final Db db;

    public StatsDao(Db db) { this.db = db; }

    public Map<String, Object> kpi(LocalDateTime from, LocalDateTime to) {
        return db.one("SELECT count(*) FILTER (WHERE e.event_type='LOGIN') AS logins, "
                + "count(*) FILTER (WHERE e.event_type='LOGIN' AND e.auth_result='SUCCESS') AS success, "
                + "count(*) FILTER (WHERE e.event_type='LOGIN' AND e.auth_result='FAILED') AS failed, "
                + "count(*) FILTER (WHERE e.event_type='LOGIN' AND e.auth_result='UNKNOWN') AS unknown, "
                + "count(*) FILTER (WHERE e.event_type='UNAUTHORIZED_ACCESS') AS unauthorized, "
                + "count(*) FILTER (WHERE r.risk_score >= 26) AS suspicious, "
                + "count(*) FILTER (WHERE r.risk_score >= 51) AS high_risk, "
                + "count(DISTINCT e.user_id) FILTER (WHERE e.event_type='LOGIN') AS users, "
                + "count(DISTINCT e.app_code) AS apps" + BASE, from, to);
    }

    public List<Map<String, Object>> trend(LocalDateTime from, LocalDateTime to) {
        return db.query("SELECT to_char(e.event_time, 'YYYY-MM-DD') AS day, count(*) FILTER (WHERE e.event_type='LOGIN') AS logins, "
                + "count(*) FILTER (WHERE e.event_type='LOGIN' AND e.auth_result='FAILED') AS failed, "
                + "count(*) FILTER (WHERE r.risk_score >= 26) AS flagged" + BASE + " GROUP BY 1 ORDER BY 1", from, to);
    }

    public List<Map<String, Object>> distribution(LocalDateTime from, LocalDateTime to) {
        return db.query("SELECT r.risk_level, count(*) AS n" + BASE + " AND r.risk_level IS NOT NULL GROUP BY 1", from, to);
    }

    public List<Map<String, Object>> topUsers(LocalDateTime from, LocalDateTime to, int limit) {
        return db.query("SELECT e.user_id, count(*) AS events, count(*) FILTER (WHERE r.risk_score >= 51) AS high_risk, max(r.risk_score) AS max_score"
                + BASE + " AND e.event_type='LOGIN' GROUP BY e.user_id HAVING max(r.risk_score) >= 26 "
                + "ORDER BY max(r.risk_score) DESC, count(*) FILTER (WHERE r.risk_score >= 51) DESC LIMIT ?", from, to, limit);
    }

    public List<Map<String, Object>> topApps(LocalDateTime from, LocalDateTime to, int limit) {
        return db.query("SELECT e.app_code, count(*) AS events, count(*) FILTER (WHERE r.risk_score >= 26) AS flagged, "
                + "count(*) FILTER (WHERE r.risk_score >= 51) AS high_risk" + BASE + " GROUP BY e.app_code "
                + "ORDER BY count(*) FILTER (WHERE r.risk_score >= 51) DESC, count(*) DESC LIMIT ?", from, to, limit);
    }

    public List<Map<String, Object>> hours(LocalDateTime from, LocalDateTime to) {
        return db.query("SELECT extract(hour FROM e.event_time)::int AS h, count(*) AS n" + BASE + " AND e.event_type='LOGIN' GROUP BY 1 ORDER BY 1", from, to);
    }

    public Map<String, Object> alertSummary() {
        return db.one("SELECT count(*) FILTER (WHERE status='OPEN') AS open, count(*) FILTER (WHERE status='IN_PROGRESS') AS in_progress, "
                + "count(*) FILTER (WHERE status<>'CLOSED' AND severity='CRITICAL') AS critical_open, count(*) AS total, "
                + "count(*) FILTER (WHERE resolution='FALSE_POSITIVE') AS false_positive, "
                + "count(*) FILTER (WHERE resolution='CONFIRMED_RISK') AS confirmed, "
                + "count(*) FILTER (WHERE status='CLOSED') AS closed, "
                + "round((avg(extract(epoch FROM (closed_at - created_at)) / 3600) FILTER (WHERE status='CLOSED'))::numeric, 1) AS avg_close_hours FROM alert");
    }

    public List<Map<String, Object>> monthly() {
        return db.query("SELECT to_char(e.event_time, 'YYYY-MM') AS month, count(*) FILTER (WHERE e.event_type='LOGIN') AS logins, "
                + "count(*) FILTER (WHERE e.event_type='LOGIN' AND e.auth_result='FAILED') AS failed, "
                + "count(*) FILTER (WHERE e.event_type='UNAUTHORIZED_ACCESS') AS unauthorized, "
                + "count(*) FILTER (WHERE r.risk_level='MEDIUM') AS medium, count(*) FILTER (WHERE r.risk_level='HIGH') AS high, "
                + "count(*) FILTER (WHERE r.risk_level='CRITICAL') AS critical FROM login_event e LEFT JOIN risk_assessment r ON r.event_id = e.id "
                + "GROUP BY 1 ORDER BY 1 DESC LIMIT 24");
    }

    public List<Map<String, Object>> alertExport(LocalDateTime from, LocalDateTime to) {
        return db.query("SELECT a.id, a.created_at, a.user_id, a.app_code, a.severity, a.max_risk_score, a.event_count, a.first_event_time, "
                + "a.last_event_time, a.status, a.resolution, a.closed_by, a.closed_at, i.analyst, a.top_factor FROM alert a "
                + "LEFT JOIN investigation i ON i.alert_id = a.id WHERE a.first_event_time >= ? AND a.first_event_time < ? ORDER BY a.id LIMIT 50000", from, to);
    }
}
