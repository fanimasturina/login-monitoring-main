package com.ilm.web.controller;

import com.ilm.core.detect.RiskResult;
import com.ilm.core.util.Util;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Small helpers shared by the controllers. */
final class View {
    static final int PAGE_SIZE = 50;

    private View() {}

    static String role(Authentication auth) {
        if (auth == null) return "";
        for (GrantedAuthority a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if (r != null && r.startsWith("ROLE_")) return r.substring(5);
        }
        return "";
    }

    /** Managers see trends, not people: user IDs, names and IP addresses are masked (NFR-S07). */
    static boolean masked(Authentication auth) { return "MANAGER".equals(role(auth)); }

    static List<Map<String, Object>> mask(List<Map<String, Object>> rows, boolean masked) {
        if (masked) for (Map<String, Object> r : rows) mask(r, true);
        return rows;
    }

    static Map<String, Object> mask(Map<String, Object> r, boolean masked) {
        if (!masked || r == null) return r;
        if (r.get("user_id") != null) r.put("user_id", Util.maskUser(String.valueOf(r.get("user_id"))));
        if (r.get("display_name") != null) r.put("display_name", "***");
        if (r.get("ip_address") != null) r.put("ip_address", Util.maskIp(String.valueOf(r.get("ip_address"))));
        if (r.get("pc_name") != null) r.put("pc_name", "***");
        if (r.containsKey("raw_text")) r.put("raw_text", "(hidden for this role)");
        return r;
    }

    static List<Map<String, Object>> factors(Object text) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RiskResult.Factor f : RiskResult.parseFactors(text == null ? null : text.toString())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("points", f.points());
            m.put("text", f.text());
            out.add(m);
        }
        return out;
    }

    static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > 100 ? t.substring(0, 100) : t);
    }

    /** Accept only one of the allowed values; anything else becomes null. */
    static String oneOf(String s, String... allowed) {
        String t = clean(s);
        if (t == null) return null;
        for (String a : allowed) if (a.equalsIgnoreCase(t)) return a;
        return null;
    }

    static LocalDateTime day(String s, boolean endExclusive) {
        try {
            LocalDate d = LocalDate.parse(s.trim());
            return endExclusive ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int page(Integer p) { return p == null || p < 1 ? 1 : Math.min(p, 100000); }

    static int pages(long total) { return (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE); }
}
