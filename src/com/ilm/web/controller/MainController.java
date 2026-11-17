package com.ilm.web.controller;

import com.ilm.web.dao.EventDao;
import com.ilm.web.dao.RiskDao;
import com.ilm.web.dao.StatsDao;
import com.ilm.web.service.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** Sign-in page, dashboard (UC-01, UC-13), login events (UC-06, UC-07, UC-09) and reports (UC-05). */
@Controller
public class MainController {
    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
    private final StatsDao stats;
    private final EventDao events;
    private final RiskDao risks;
    private final AuditService audit;

    public MainController(StatsDao stats, EventDao events, RiskDao risks, AuditService audit) {
        this.stats = stats;
        this.events = events;
        this.risks = risks;
        this.audit = audit;
    }

    @GetMapping("/login")
    public String login(Authentication auth) { return auth != null && auth.isAuthenticated() ? "redirect:/dashboard" : "login"; }

    @GetMapping("/")
    public String home() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(name = "days", required = false) Integer days, Model model, Authentication auth) {
        int d = days == null ? 30 : List.of(7, 30, 90, 365).contains(days) ? days : 30;
        // The period ends at the newest stored event, so historical uploads are visible too.
        LocalDateTime latest = events.latestEventTime();
        LocalDateTime to = (latest == null ? LocalDateTime.now() : latest).toLocalDate().plusDays(1).atStartOfDay();
        LocalDateTime from = to.minusDays(d);
        boolean masked = View.masked(auth);
        model.addAttribute("days", d);
        model.addAttribute("from", from.toLocalDate().format(DAY));
        model.addAttribute("to", to.toLocalDate().minusDays(1).format(DAY));
        model.addAttribute("hasData", latest != null);
        model.addAttribute("kpi", stats.kpi(from, to));
        model.addAttribute("trend", stats.trend(from, to));
        model.addAttribute("dist", stats.distribution(from, to));
        model.addAttribute("topUsers", View.mask(stats.topUsers(from, to, 8), masked));
        model.addAttribute("topApps", stats.topApps(from, to, 8));
        model.addAttribute("hours", stats.hours(from, to));
        model.addAttribute("alertSummary", stats.alertSummary());
        model.addAttribute("latestAlerts", View.mask(risks.alerts(null, null, 6, 0), masked));
        return "dashboard";
    }

    @GetMapping("/events")
    public String events(@RequestParam(name = "user", required = false) String user, @RequestParam(name = "app", required = false) String app,
            @RequestParam(name = "level", required = false) String level, @RequestParam(name = "result", required = false) String result,
            @RequestParam(name = "type", required = false) String type, @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to, @RequestParam(name = "page", required = false) Integer page,
            Model model, Authentication auth) {
        boolean masked = View.masked(auth);
        EventDao.Filter f = new EventDao.Filter(masked ? null : View.clean(user), View.clean(app),
                View.oneOf(level, "LOW", "MEDIUM", "HIGH", "CRITICAL"), View.oneOf(result, "SUCCESS", "FAILED", "UNKNOWN"),
                View.oneOf(type, "LOGIN", "LOGOUT", "UNAUTHORIZED_ACCESS"), from == null ? null : View.day(from, false), to == null ? null : View.day(to, true));
        long total = events.searchCount(f);
        int p = Math.min(View.page(page), View.pages(total));
        model.addAttribute("rows", View.mask(events.search(f, View.PAGE_SIZE, (p - 1) * View.PAGE_SIZE), masked));
        model.addAttribute("total", total);
        model.addAttribute("page", p);
        model.addAttribute("pages", View.pages(total));
        model.addAttribute("apps", events.appCodes());
        model.addAttribute("f", f);
        model.addAttribute("fFrom", f.from() == null ? "" : f.from().toLocalDate().toString());
        model.addAttribute("fTo", f.to() == null ? "" : f.to().toLocalDate().minusDays(1).toString());
        return "events";
    }

    @GetMapping("/events/{id}")
    public String event(@PathVariable("id") long id, Model model, Authentication auth) {
        Map<String, Object> e = events.detail(id);
        if (e == null) {
            model.addAttribute("title", "Event not found");
            model.addAttribute("message", "There is no login event with number " + id + ".");
            return "error";
        }
        boolean masked = View.masked(auth);
        String userId = String.valueOf(e.get("user_id"));
        model.addAttribute("factors", View.factors(e.get("factors")));
        model.addAttribute("profile", risks.profile(userId));
        model.addAttribute("history", View.mask(events.userRecent(userId, ((java.sql.Timestamp) e.get("event_time")).toLocalDateTime(), 15), masked));
        model.addAttribute("e", View.mask(e, masked));
        return "event";
    }

    @GetMapping("/reports")
    public String reports(Model model) {
        model.addAttribute("monthly", stats.monthly());
        model.addAttribute("alertSummary", stats.alertSummary());
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("monthAgo", LocalDate.now().minusDays(30).toString());
        return "reports";
    }

    /** Alert list as CSV for audit evidence. The export itself is written to the audit trail. */
    @GetMapping("/reports/alerts.csv")
    public void alertsCsv(@RequestParam("from") String from, @RequestParam("to") String to, Authentication auth, HttpServletResponse response) throws IOException {
        LocalDateTime f = View.day(from, false), t = View.day(to, true);
        if (f == null || t == null || !f.isBefore(t)) { response.sendError(400, "Invalid date range"); return; }
        boolean masked = View.masked(auth);
        List<Map<String, Object>> rows = View.mask(stats.alertExport(f, t), masked);
        audit.log(auth.getName(), "EXPORT_ALERTS", "alerts.csv", from + " to " + to + ", " + rows.size() + " rows");
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"ilm-alerts-" + f.toLocalDate() + "-to-" + t.toLocalDate().minusDays(1) + ".csv\"");
        response.setHeader("Cache-Control", "no-store");
        PrintWriter w = response.getWriter();
        w.write('﻿'); // so Excel opens it as UTF-8
        if (!rows.isEmpty()) w.println(String.join(",", rows.get(0).keySet()));
        for (Map<String, Object> r : rows) {
            StringBuilder sb = new StringBuilder();
            for (Object v : r.values()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(csv(v));
            }
            w.println(sb);
        }
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v.toString();
        // Stop spreadsheet programs from running a cell as a formula (CSV injection).
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
