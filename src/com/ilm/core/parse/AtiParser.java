package com.ilm.core.parse;

import com.ilm.core.model.LoginEvent;
import com.ilm.core.util.Util;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audit Trail Integrator "user activity" rows (xlsx/csv export today, DB rows later).
 * Expected columns: Aua Id, App Code, User Id, Action Code, Menu, Description, Parameters,
 * Date Activity, Endpoint. Column order does not matter; names are matched loosely.
 */
public final class AtiParser {
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS][.SS][.S]"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));
    private static final LocalDateTime EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    private AtiParser() {}

    public static ParseResult parse(List<List<Object>> rows) {
        ParseResult out = new ParseResult();
        if (rows.isEmpty()) { out.error(1, "", "File is empty"); return out; }
        Map<String, Integer> col = new HashMap<>();
        List<Object> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i) != null) col.put(key(header.get(i).toString()), i);
        }
        for (String need : new String[] {"userid", "appcode", "dateactivity", "actioncode"}) {
            if (!col.containsKey(need)) {
                out.error(1, header.toString(), "Missing column '" + need + "'. Is this an ATI user activity export?");
                return out;
            }
        }
        for (int r = 1; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            int lineNo = r + 1;
            if (row.stream().allMatch(v -> v == null || v.toString().isBlank())) continue;
            String raw = Util.cut(row.toString(), 2000);
            String action = str(row, col, "actioncode");
            String desc = str(row, col, "description");
            String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
            boolean login = a.contains("login") || a.contains("logon") || a.contains("signin");
            boolean logout = a.contains("logout") || a.contains("logoff") || a.contains("signout");
            if (!login && !logout) { out.skipped++; continue; }
            String user = str(row, col, "userid");
            String app = str(row, col, "appcode");
            LocalDateTime time = date(cell(row, col, "dateactivity"));
            if (Util.blank(user) || Util.blank(app) || time == null) {
                out.error(lineNo, raw, "Missing User Id, App Code or Date Activity (BR-01)");
                continue;
            }
            LoginEvent e = new LoginEvent();
            e.sourceType = LoginEvent.SRC_ATI;
            e.appCode = Util.cut(app.trim().toUpperCase(Locale.ROOT), 50);
            e.userId = Util.cut(user.trim().toLowerCase(Locale.ROOT), 100);
            e.eventTime = time;
            e.eventType = logout ? LoginEvent.TYPE_LOGOUT : LoginEvent.TYPE_LOGIN;
            String d = desc == null ? "" : desc.toLowerCase(Locale.ROOT);
            boolean failed = a.contains("fail") || d.contains("fail") || d.contains("gagal") || d.contains("invalid") || d.contains("denied");
            e.result = failed ? LoginEvent.RES_FAILED : LoginEvent.RES_SUCCESS;
            if (desc != null && d.startsWith("user ") && d.endsWith(" logged in")) {
                e.displayName = Util.cut(desc.substring(5, desc.length() - " logged in".length()).trim(), 200);
            }
            e.endpoint = Util.cut(str(row, col, "endpoint"), 200);
            e.ipAddress = null;
            e.ipClass = "NONE";
            Object aua = cell(row, col, "auaid");
            e.sourceRef = aua == null ? String.valueOf(lineNo) : plain(aua);
            e.rawText = raw;
            e.eventHash = Util.sha256(aua != null
                    ? "ATI|" + plain(aua)
                    : "ATI|" + e.appCode + "|" + e.userId + "|" + e.eventTime + "|" + e.eventType + "|" + e.endpoint);
            out.events.add(e);
        }
        return out;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Object cell(List<Object> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? null : row.get(i);
    }

    private static String str(List<Object> row, Map<String, Integer> col, String name) {
        Object v = cell(row, col, name);
        return v == null ? null : plain(v);
    }

    private static String plain(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        return v.toString();
    }

    static LocalDateTime date(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime t) return t;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.util.Date t) return new java.sql.Timestamp(t.getTime()).toLocalDateTime();
        if (v instanceof Number n) { // Excel serial date: days since 1899-12-30
            double serial = n.doubleValue();
            if (serial < 20000 || serial > 80000) return null;
            return EXCEL_EPOCH.plusSeconds(Math.round(serial * 86400d));
        }
        String s = v.toString().trim();
        for (DateTimeFormatter f : FORMATS) {
            try { return LocalDateTime.parse(s, f); } catch (RuntimeException ignored) { /* try next */ }
        }
        try { return date(Double.valueOf(s)); } catch (NumberFormatException ignored) { return null; }
    }
}
