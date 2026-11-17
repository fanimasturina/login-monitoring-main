package com.ilm.core.detect;

import com.ilm.core.model.LoginEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory behavioural history: one profile per user plus short recent windows per application. */
public final class DetectionState {
    /** Behavioural profile of one user (FR-11..FR-16). Histograms count non-failed logins only. */
    public static final class UserHist {
        public final int[] hour = new int[24];
        public final int[] dow = new int[7];
        public int profileLogins;   // logins counted in the histograms
        public int total;
        public int failed;
        public int activeDays;
        public LocalDate lastDay;
        public LocalDateTime firstSeen;
        public LocalDateTime lastSeen;
        public boolean everSucceeded;
        public final Set<String> apps = new HashSet<>();
        /** {timeMs, failedFlag, sourceCode} for the last 24 hours. */
        final ArrayDeque<long[]> recent = new ArrayDeque<>();

        public double avgDaily() { return activeDays == 0 ? 0 : (double) total / activeDays; }
        public double failureRatio() { return total == 0 ? 0 : (double) failed / total; }
    }

    final Map<String, UserHist> users = new HashMap<>();
    final int[] globalHour = new int[24];
    final int[] globalDow = new int[7];
    int globalLogins;
    /** per app: {timeMs, userHash} of failed logins in the last 5 minutes. */
    final Map<String, ArrayDeque<long[]>> recentFailures = new HashMap<>();
    /** per app: timeMs of unauthorized-access events in the last 2 minutes. */
    final Map<String, ArrayDeque<long[]>> recentUnauthorized = new HashMap<>();
    LocalDateTime lastTime;

    public Map<String, UserHist> users() { return users; }
    public LocalDateTime lastTime() { return lastTime; }

    /** The same login can arrive from two sources (ATI and the app log); bursts are counted per source. */
    static long src(LoginEvent e) { return e.sourceType == null ? 0 : e.sourceType.hashCode(); }

    static long ms(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC) * 1000 + t.getNano() / 1_000_000;
    }

    /** Add an event to the history. Always call after features were extracted for it. */
    void update(LoginEvent e) {
        long t = ms(e.eventTime);
        if (lastTime == null || e.eventTime.isAfter(lastTime)) lastTime = e.eventTime;
        String app = e.appCode == null ? "" : e.appCode;
        if (e.isUnauthorized()) {
            ArrayDeque<long[]> q = recentUnauthorized.computeIfAbsent(app, k -> new ArrayDeque<>());
            q.addLast(new long[] {t});
            while (!q.isEmpty() && q.peekFirst()[0] < t - 120_000) q.pollFirst();
            return;
        }
        if (!e.isLogin()) return;
        UserHist h = users.computeIfAbsent(e.userId, k -> new UserHist());
        if (h.firstSeen == null) h.firstSeen = e.eventTime;
        h.lastSeen = e.eventTime;
        h.total++;
        LocalDate day = e.eventTime.toLocalDate();
        if (!day.equals(h.lastDay)) { h.activeDays++; h.lastDay = day; }
        h.recent.addLast(new long[] {t, e.isFailed() ? 1 : 0, src(e)});
        while (!h.recent.isEmpty() && h.recent.peekFirst()[0] < t - 86_400_000L) h.recent.pollFirst();
        if (e.isFailed()) {
            h.failed++;
            ArrayDeque<long[]> q = recentFailures.computeIfAbsent(app, k -> new ArrayDeque<>());
            q.addLast(new long[] {t, e.userId.hashCode()});
            while (!q.isEmpty() && q.peekFirst()[0] < t - 300_000) q.pollFirst();
        } else {
            int hr = e.eventTime.getHour();
            int d = e.eventTime.getDayOfWeek().getValue() - 1;
            h.hour[hr]++; h.dow[d]++; h.profileLogins++;
            globalHour[hr]++; globalDow[d]++; globalLogins++;
            if (e.isSuccess()) h.everSucceeded = true;
            if (e.appCode != null) h.apps.add(e.appCode);
        }
    }
}
