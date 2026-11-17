package com.ilm.web.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * Small JDBC helper: every statement is a PreparedStatement with bound parameters
 * (no string concatenation of user input), results are read from the ResultSet into maps.
 */
@Component
public class Db {
    public interface Work<T> { T run(Connection c) throws SQLException; }
    public interface RowHandler { void row(ResultSet rs) throws SQLException; }

    private final DataSource ds;

    public Db(DataSource ds) { this.ds = ds; }

    /** Run several statements in one transaction. */
    public <T> T tx(Work<T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection c = ds.getConnection()) {
            return query(c, sql, params);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public Map<String, Object> one(String sql, Object... params) {
        List<Map<String, Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String sql, Object... params) {
        Map<String, Object> row = one(sql, params);
        return row == null ? 0 : ((Number) row.values().iterator().next()).longValue();
    }

    public int update(String sql, Object... params) {
        try (Connection c = ds.getConnection()) {
            return update(c, sql, params);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Read a large result row by row without keeping it in memory. */
    public void stream(String sql, RowHandler handler, Object... params) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false); // PostgreSQL only uses a cursor inside a transaction
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setFetchSize(2000);
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) handler.row(rs);
                }
            } finally {
                c.rollback();
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public static List<Map<String, Object>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** For INSERT ... RETURNING id. Returns null when no row came back (e.g. ON CONFLICT DO NOTHING). */
    public static Long insertReturningId(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof LocalDateTime t) ps.setTimestamp(i + 1, Timestamp.valueOf(t));
            else ps.setObject(i + 1, p);
        }
    }

    public static final class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DbException(SQLException cause) { super("Database error: " + cause.getMessage(), cause); }
    }
}
