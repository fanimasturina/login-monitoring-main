package com.ilm.core.detect;

import com.ilm.core.detect.DetectionState.UserHist;
import com.ilm.core.ml.IsolationForest;
import com.ilm.core.model.LoginEvent;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detect -> Score -> Explain for one event at a time (hybrid approach: known-pattern rules
 * plus Isolation Forest for behaviour that no rule describes).
 * Not thread-safe: callers must process events one by one, in time order.
 */
public final class DetectionEngine {
    public static final String[] FEATURES = {
        "hour", "weekend", "hourRarity", "dayRarity", "newApp", "burst60s", "burst10m", "failures10m",
        "failed", "newUser", "gapLog", "unauthorized2m", "dailyRatio", "privileged", "failingUsers5m"};
    private static final String[] FEATURE_LABELS = {
        "time of day", "weekend login", "hour is rare for this user", "day is rare for this user",
        "application is new for this user", "many logins within 1 minute", "many logins within 10 minutes",
        "failed logins in the last 10 minutes", "login failed", "user ID not seen before",
        "time since previous login", "unauthorized-access events nearby", "more logins than usual today",
        "privileged account", "several user IDs failing together"};
    private static final double MAX_GAP_SECONDS = 30d * 86400;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

    private final Settings cfg;
    private final IsolationForest model;
    private final DetectionState state = new DetectionState();

    public DetectionEngine(Settings cfg, IsolationForest model) {
        this.cfg = cfg;
        this.model = model != null && model.featureCount() == FEATURES.length ? model : null;
    }

    public DetectionState state() { return state; }
    public boolean hasModel() { return model != null; }

    /** Values the rules need, computed once per event before the history is updated. */
    private static final class Ctx {
        double[] x = new double[FEATURES.length];
        UserHist h;
        boolean mature, night, offHours, weekend, privileged;
        int burst5s, burst60s, burst10m, failures10m, last24h, unauth2m, failingUsers5m;
        int hourHits, hourTotal;
    }

    /** Warm-up: remember an already scored event without scoring it again. */
    public void observe(LoginEvent e) { state.update(e); }

    /** Score one event, then add it to the history. Returns null for events that are not scored (logout). */
    public RiskResult process(LoginEvent e) {
        RiskResult r;
        if (e.isUnauthorized()) r = scoreUnauthorized(e);
        else if (e.isLogin()) r = scoreLogin(e, extract(e));
        else r = null;
        state.update(e);
        return r;
    }

    /** Feature rows for training: replay events in time order through a fresh history. */
    public static double[][] trainingRows(List<LoginEvent> sortedEvents, Settings cfg) {
        DetectionEngine eng = new DetectionEngine(cfg, null);
        List<double[]> rows = new ArrayList<>();
        for (LoginEvent e : sortedEvents) {
            if (e.isLogin()) rows.add(eng.extract(e).x);
            eng.state.update(e);
        }
        return rows.toArray(new double[0][]);
    }

    public static IsolationForest train(List<LoginEvent> sortedEvents, Settings cfg, long seed) {
        return IsolationForest.fit(trainingRows(sortedEvents, cfg), cfg.i("ml.trees"), cfg.i("ml.sample.size"), seed);
    }

    private Ctx extract(LoginEvent e) {
        Ctx c = new Ctx();
        LocalDateTime t = e.eventTime;
        long now = DetectionState.ms(t);
        String app = e.appCode == null ? "" : e.appCode;
        UserHist h = state.users.get(e.userId);
        c.h = h;
        int hr = t.getHour();
        int dow = t.getDayOfWeek().getValue() - 1;
        c.weekend = t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY;
        int ns = cfg.i("night.hour.start"), ne = cfg.i("night.hour.end");
        c.night = ns > ne ? (hr >= ns || hr < ne) : (hr >= ns && hr < ne);
        c.offHours = c.weekend || hr < cfg.i("business.hour.start") || hr >= cfg.i("business.hour.end");
        c.mature = h != null && h.profileLogins >= cfg.i("profile.min.events")
                && Duration.between(h.firstSeen, t).toDays() >= cfg.i("profile.min.days");

        double hourRarity = 0, dayRarity = 0;
        if (c.mature) {
            c.hourHits = h.hour[(hr + 23) % 24] + h.hour[hr] + h.hour[(hr + 1) % 24];
            c.hourTotal = h.profileLogins;
            hourRarity = 1 - Math.min(1, ((double) c.hourHits / h.profileLogins) / 0.25);
            dayRarity = 1 - Math.min(1, ((double) h.dow[dow] / h.profileLogins) / 0.10);
        } else if (state.globalLogins >= 50) { // short history: compare with all users instead
            int hits = state.globalHour[(hr + 23) % 24] + state.globalHour[hr] + state.globalHour[(hr + 1) % 24];
            hourRarity = 1 - Math.min(1, ((double) hits / state.globalLogins) / 0.25);
            dayRarity = 1 - Math.min(1, ((double) state.globalDow[dow] / state.globalLogins) / 0.10);
        }
        boolean newApp = h != null && h.profileLogins >= 5 && e.appCode != null && !h.apps.contains(e.appCode);
        double gap = MAX_GAP_SECONDS;
        long src = DetectionState.src(e);
        if (h != null) {
            for (long[] ev : h.recent) {
                long age = now - ev[0];
                if (age < 0) continue;
                if (age <= 600_000 && ev[1] == 1) c.failures10m++;
                if (ev[2] != src) continue; // same login reported by another source: not a repeat
                c.last24h++;
                if (age <= 600_000) c.burst10m++;
                if (age <= 60_000) c.burst60s++;
                if (age <= 5_000) c.burst5s++;
            }
            if (h.lastSeen != null) gap = Math.min(MAX_GAP_SECONDS, Math.max(0, Duration.between(h.lastSeen, t).toSeconds()));
        }
        boolean knownUser = h != null && (h.everSucceeded || h.profileLogins >= 3);
        var unauth = state.recentUnauthorized.get(app);
        if (unauth != null) for (long[] u : unauth) if (now - u[0] >= 0 && now - u[0] <= 120_000) c.unauth2m++;
        Set<Long> failing = new HashSet<>();
        var fails = state.recentFailures.get(app);
        if (fails != null) for (long[] f : fails) if (now - f[0] >= 0 && now - f[0] <= 300_000) failing.add(f[1]);
        if (e.isFailed()) failing.add((long) e.userId.hashCode());
        c.failingUsers5m = failing.size();
        String uid = e.userId.toLowerCase(Locale.ROOT);
        c.privileged = cfg.list("privileged.patterns").stream().anyMatch(uid::contains);
        double dailyRatio = c.mature ? (c.last24h + 1) / (h.avgDaily() + 1) : 1;

        c.x[0] = hr + t.getMinute() / 60.0;
        c.x[1] = c.weekend ? 1 : 0;
        c.x[2] = hourRarity;
        c.x[3] = dayRarity;
        c.x[4] = newApp ? 1 : 0;
        c.x[5] = c.burst60s;
        c.x[6] = c.burst10m;
        c.x[7] = c.failures10m;
        c.x[8] = e.isFailed() ? 1 : 0;
        c.x[9] = knownUser ? 0 : 1;
        c.x[10] = Math.log10(1 + gap);
        c.x[11] = c.unauth2m;
        c.x[12] = Math.min(20, dailyRatio);
        c.x[13] = c.privileged ? 1 : 0;
        c.x[14] = c.failingUsers5m;
        return c;
    }

    private RiskResult scoreLogin(LoginEvent e, Ctx c) {
        RiskResult r = new RiskResult();
        String when = e.eventTime.format(HM);
        if (e.isSuccess() && c.failures10m >= 3) {
            add(r, "FAIL_THEN_SUCCESS", cfg.i("rule.fail.then.success"),
                    "Successful login right after " + c.failures10m + " failed attempts in 10 minutes");
        }
        if (e.isFailed()) {
            int n = c.failures10m + 1;
            if (n >= 5) add(r, "REPEATED_FAILURES", cfg.i("rule.repeated.failures"), n + " failed logins in 10 minutes (possible password guessing)");
            else if (n >= 3) add(r, "REPEATED_FAILURES", cfg.i("rule.repeated.failures") / 2, n + " failed logins in 10 minutes");
            if (c.h == null) add(r, "UNKNOWN_USER", cfg.i("rule.unknown.user"), "Failed login with a user ID never seen before (typo or guessing)");
        }
        if (c.burst5s + 1 >= 3) {
            add(r, "BURST", cfg.i("rule.burst"), (c.burst5s + 1) + " login requests within 5 seconds (faster than a person can type)");
        }
        if (c.night) add(r, "NIGHT", cfg.i("rule.night"), "Login at night (" + when + ")");
        else if (c.offHours) add(r, "OFF_HOURS", cfg.i("rule.off.hours"), "Login outside working hours (" + when + ")");
        if (c.mature && c.x[2] >= 0.9) {
            add(r, "UNUSUAL_HOUR", cfg.i("rule.unusual.hour"), "Unusual hour for this user: only " + c.hourHits + " of "
                    + c.hourTotal + " past logins were around " + String.format("%02d:00", e.eventTime.getHour()));
        }
        if (c.x[4] == 1) add(r, "NEW_APP", cfg.i("rule.new.app"), "First login of this user to " + e.appCode);
        if (c.failingUsers5m >= 4) {
            add(r, "ENUMERATION", cfg.i("rule.enumeration"), c.failingUsers5m + " different user IDs failed to log in within 5 minutes");
        }
        if (c.privileged) {
            add(r, "PRIVILEGED", cfg.i("rule.privileged"), "Privileged account");
            if (c.offHours || c.night) add(r, "PRIVILEGED_OFF_HOURS", cfg.i("rule.privileged.off.hours"), "Privileged account used outside working hours");
        }
        if (c.unauth2m >= 5) {
            add(r, "UNAUTHORIZED_NEARBY", cfg.i("rule.unauthorized.nearby"), c.unauth2m + " unauthorized-access events in the 2 minutes before this login");
        }
        if (c.mature && c.x[12] >= 3 && c.last24h + 1 >= 6) {
            add(r, "VOLUME", cfg.i("rule.volume"), (c.last24h + 1) + " logins in 24 hours; this user's average is "
                    + String.format(Locale.ENGLISH, "%.1f", c.h.avgDaily()) + " per active day");
        }
        if (model != null && cfg.bool("ml.enabled")) {
            double s = model.score(c.x);
            r.anomalyScore = s;
            double thr = cfg.dbl("ml.threshold");
            if (s >= thr) {
                double strength = Math.min(1, (s - thr) / 0.15);
                r.mlPoints = (int) Math.round(cfg.i("ml.max.points") * (0.5 + 0.5 * strength));
                r.factors.add(new RiskResult.Factor("ML_ANOMALY", r.mlPoints, "Machine learning: behaviour differs from the learned pattern (anomaly score "
                        + String.format(Locale.ENGLISH, "%.2f", s) + ")" + drivers(c.x)));
            }
        }
        if (!c.mature) {
            r.factors.add(new RiskResult.Factor("SHORT_BASELINE", 0, c.h == null
                    ? "No history for this user yet; compared with all users"
                    : "User has less than " + cfg.i("profile.min.days") + " days of history; compared with all users"));
        }
        if (LoginEvent.RES_UNKNOWN.equals(e.result)) {
            r.factors.add(new RiskResult.Factor("RESULT_UNKNOWN", 0, "The source log does not say whether this login succeeded"));
        }
        if ("LOOPBACK".equals(e.ipClass)) {
            r.factors.add(new RiskResult.Factor("IP_NOT_CLIENT", 0, "IP address is the proxy/loopback, not the user's device, so network checks are skipped"));
        }
        finish(r);
        return r;
    }

    private String drivers(double[] x) {
        double[] contrib = model.contributions(x);
        StringBuilder sb = new StringBuilder();
        boolean[] used = new boolean[contrib.length];
        for (int k = 0; k < 3; k++) {
            int best = -1;
            for (int i = 0; i < contrib.length; i++) {
                if (!used[i] && contrib[i] > 0.005 && (best < 0 || contrib[i] > contrib[best])) best = i;
            }
            if (best < 0) break;
            used[best] = true;
            sb.append(sb.length() == 0 ? ". Main drivers: " : "; ").append(FEATURE_LABELS[best]);
        }
        return sb.toString();
    }

    private RiskResult scoreUnauthorized(LoginEvent e) {
        RiskResult r = new RiskResult();
        long now = DetectionState.ms(e.eventTime);
        int n = 1;
        var q = state.recentUnauthorized.get(e.appCode == null ? "" : e.appCode);
        if (q != null) for (long[] u : q) if (now - u[0] >= 0 && now - u[0] <= 60_000) n++;
        int base = cfg.i("rule.unauthorized.burst");
        if (n >= 10) add(r, "UNAUTHORIZED_BURST", Math.min(100, base + 20), n + " unauthorized-access attempts within 60 seconds");
        else if (n >= 5) add(r, "UNAUTHORIZED_BURST", base, n + " unauthorized-access attempts within 60 seconds");
        int hr = e.eventTime.getHour();
        int ns = cfg.i("night.hour.start"), ne = cfg.i("night.hour.end");
        boolean night = ns > ne ? (hr >= ns || hr < ne) : (hr >= ns && hr < ne);
        boolean weekend = e.eventTime.getDayOfWeek().getValue() >= 6;
        if (night) add(r, "NIGHT", cfg.i("rule.night"), "Unauthorized access attempt at night (" + e.eventTime.format(HM) + ")");
        else if (weekend || hr < cfg.i("business.hour.start") || hr >= cfg.i("business.hour.end")) {
            add(r, "OFF_HOURS", cfg.i("rule.off.hours"), "Unauthorized access attempt outside working hours (" + e.eventTime.format(HM) + ")");
        }
        r.factors.add(new RiskResult.Factor("NO_USER", 0, "This log has no user ID; usually an expired session or a page opened without login"));
        finish(r);
        return r;
    }

    private static void add(RiskResult r, String code, int points, String text) {
        if (points <= 0) return;
        r.rulePoints += points;
        r.factors.add(new RiskResult.Factor(code, points, text));
    }

    private static void finish(RiskResult r) {
        r.riskScore = Math.max(0, Math.min(100, r.rulePoints + r.mlPoints));
        r.riskLevel = RiskResult.level(r.riskScore);
        r.factors.sort((a, b) -> Integer.compare(b.points(), a.points()));
    }
}
