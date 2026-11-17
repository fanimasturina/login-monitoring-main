package com.ilm.core.detect;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detection settings with safe defaults. Admins change them on the Settings page (UC-03, UC-11). */
public final class Settings {
    /** key -> {default, description}. Order here is the order shown on the Settings page. */
    public static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();
    static {
        d("alert.threshold", "51", "Create an alert when risk score is at least this value (0-100). 51 = High and Critical (BR-03).");
        d("business.hour.start", "7", "Start of normal working hours (0-23).");
        d("business.hour.end", "19", "End of normal working hours (0-23). Logins at or after this hour are outside hours.");
        d("night.hour.start", "22", "Start of night hours (0-23).");
        d("night.hour.end", "5", "End of night hours (0-23).");
        d("profile.min.days", "30", "Days of history needed before a user's own profile is trusted (BR-04).");
        d("profile.min.events", "10", "Logins needed before a user's own profile is trusted.");
        d("privileged.patterns", "superadm,admin,root,secadm", "User IDs containing any of these words are treated as privileged accounts.");
        d("ml.enabled", "true", "Use the machine learning model in the risk score.");
        d("ml.threshold", "0.60", "Anomaly score (0-1) from which the model adds risk points.");
        d("ml.max.points", "40", "Maximum risk points the model can add.");
        d("ml.trees", "100", "Number of trees in the Isolation Forest.");
        d("ml.sample.size", "256", "Rows sampled per tree.");
        d("ml.min.samples", "200", "Login events needed before the first model is trained automatically.");
        d("rule.fail.then.success", "30", "Points: successful login right after 3 or more failures.");
        d("rule.repeated.failures", "35", "Points: 5 or more failed logins in 10 minutes (half for 3-4).");
        d("rule.burst", "25", "Points: 3 or more login requests within 5 seconds.");
        d("rule.off.hours", "20", "Points: login outside working hours or on weekend.");
        d("rule.night", "30", "Points: login at night (replaces the off-hours points).");
        d("rule.unusual.hour", "20", "Points: hour is unusual for this user's own history.");
        d("rule.new.app", "15", "Points: first time this user logs in to this application.");
        d("rule.unknown.user", "10", "Points: failed login with a user ID never seen before.");
        d("rule.enumeration", "35", "Points: 4 or more different user IDs failing within 5 minutes.");
        d("rule.privileged", "10", "Points: privileged account.");
        d("rule.privileged.off.hours", "15", "Extra points: privileged account outside working hours.");
        d("rule.unauthorized.nearby", "15", "Points: 5 or more unauthorized-access events in the 2 minutes before.");
        d("rule.unauthorized.burst", "45", "Points: 5 or more unauthorized-access events within 60 seconds (65 for 10 or more).");
        d("rule.volume", "15", "Points: user's logins in 24 hours are 3x their daily average.");
    }

    private static void d(String k, String v, String desc) { DEFAULTS.put(k, new String[] {v, desc}); }

    private final Map<String, String> values = new LinkedHashMap<>();

    public Settings() { this(Map.of()); }

    public Settings(Map<String, String> overrides) {
        DEFAULTS.forEach((k, v) -> values.put(k, v[0]));
        overrides.forEach((k, v) -> { if (v != null && DEFAULTS.containsKey(k)) values.put(k, v.trim()); });
    }

    public String get(String k) { return values.get(k); }

    public int i(String k) {
        try { return Integer.parseInt(values.get(k)); } catch (RuntimeException e) { return Integer.parseInt(DEFAULTS.get(k)[0]); }
    }

    public double dbl(String k) {
        try { return Double.parseDouble(values.get(k)); } catch (RuntimeException e) { return Double.parseDouble(DEFAULTS.get(k)[0]); }
    }

    public boolean bool(String k) { return "true".equalsIgnoreCase(values.get(k)); }

    public List<String> list(String k) {
        return Arrays.stream(values.getOrDefault(k, "").toLowerCase().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Map<String, String> asMap() { return values; }

    /** Returns an error message, or null when the value is acceptable. */
    public static String validate(String key, String value) {
        if (!DEFAULTS.containsKey(key)) return "Unknown setting";
        if (value == null || value.isBlank()) return "Value is required";
        String v = value.trim();
        try {
            if (key.equals("privileged.patterns")) return v.length() > 300 ? "Too long" : null;
            if (key.equals("ml.enabled")) return v.equals("true") || v.equals("false") ? null : "Use true or false";
            if (key.equals("ml.threshold")) { double x = Double.parseDouble(v); return x >= 0.3 && x <= 0.95 ? null : "Use a value between 0.30 and 0.95"; }
            int n = Integer.parseInt(v);
            if (key.contains(".hour.")) return n >= 0 && n <= 23 ? null : "Use an hour between 0 and 23";
            if (key.equals("ml.trees")) return n >= 10 && n <= 500 ? null : "Use 10 to 500";
            if (key.equals("ml.sample.size")) return n >= 32 && n <= 4096 ? null : "Use 32 to 4096";
            if (key.equals("ml.min.samples")) return n >= 50 && n <= 1000000 ? null : "Use 50 or more";
            if (key.startsWith("profile.")) return n >= 0 && n <= 365 ? null : "Use 0 to 365";
            return n >= 0 && n <= 100 ? null : "Use a value between 0 and 100";
        } catch (NumberFormatException e) {
            return "Not a valid number";
        }
    }
}
