package com.ilm.web.controller;

import com.ilm.web.dao.EventDao;
import com.ilm.web.dao.RiskDao;
import com.ilm.web.service.AuditService;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Alerts and investigation (UC-02, UC-08, UC-10). Alert states: OPEN -> IN_PROGRESS -> CLOSED,
 * or OPEN -> CLOSED for duplicates. Analysts record a result; they cannot change the score (BR-06).
 */
@Controller
public class AlertController {
    private final RiskDao risks;
    private final EventDao events;
    private final AuditService audit;

    public AlertController(RiskDao risks, EventDao events, AuditService audit) {
        this.risks = risks;
        this.events = events;
        this.audit = audit;
    }

    @GetMapping("/alerts")
    public String list(@RequestParam(name = "status", required = false) String status, @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "page", required = false) Integer page, Model model, Authentication auth) {
        String s = View.oneOf(status, "OPEN", "IN_PROGRESS", "CLOSED");
        String sev = View.oneOf(severity, "MEDIUM", "HIGH", "CRITICAL");
        long total = risks.alertCount(s, sev);
        int p = Math.min(View.page(page), View.pages(total));
        List<Map<String, Object>> rows = risks.alerts(s, sev, View.PAGE_SIZE, (p - 1) * View.PAGE_SIZE);
        model.addAttribute("rows", View.mask(rows, View.masked(auth)));
        model.addAttribute("total", total);
        model.addAttribute("page", p);
        model.addAttribute("pages", View.pages(total));
        model.addAttribute("status", s == null ? "" : s);
        model.addAttribute("severity", sev == null ? "" : sev);
        return "alerts";
    }

    @GetMapping("/alerts/{id}")
    public String detail(@PathVariable("id") long id, Model model, Authentication auth) {
        Map<String, Object> a = risks.alert(id);
        if (a == null) {
            model.addAttribute("title", "Alert not found");
            model.addAttribute("message", "There is no alert with number " + id + ".");
            return "error";
        }
        boolean masked = View.masked(auth);
        String userId = String.valueOf(a.get("user_id"));
        List<Map<String, Object>> evs = risks.alertEvents(id);
        for (Map<String, Object> e : evs) e.put("factorList", View.factors(e.get("factors")));
        model.addAttribute("events", View.mask(evs, masked));
        model.addAttribute("notes", risks.notes(id));
        model.addAttribute("profile", risks.profile(userId));
        model.addAttribute("history", View.mask(events.userRecent(userId, ((Timestamp) a.get("last_event_time")).toLocalDateTime(), 20), masked));
        model.addAttribute("a", View.mask(a, masked));
        return "alert";
    }

    @PostMapping("/alerts/{id}/start")
    public String start(@PathVariable("id") long id, Authentication auth, RedirectAttributes ra) {
        if (risks.startInvestigation(id, auth.getName())) {
            audit.log(auth.getName(), "ALERT_STATUS", "alert #" + id, "OPEN -> IN_PROGRESS (investigation started)");
            ra.addFlashAttribute("ok", "Investigation started. The alert is now In progress.");
        } else {
            ra.addFlashAttribute("err", "This alert is not Open any more. Someone else may have started it.");
        }
        return "redirect:/alerts/" + id;
    }

    @PostMapping("/alerts/{id}/note")
    public String note(@PathVariable("id") long id, @RequestParam("note") String note, Authentication auth, RedirectAttributes ra) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty() || n.length() > 4000) {
            ra.addFlashAttribute("err", "Write a note of 1 to 4000 characters.");
        } else if (risks.alert(id) != null) {
            risks.addNote(id, auth.getName(), n);
            audit.log(auth.getName(), "ALERT_NOTE", "alert #" + id, n.length() > 200 ? n.substring(0, 200) + "..." : n);
            ra.addFlashAttribute("ok", "Note saved.");
        }
        return "redirect:/alerts/" + id;
    }

    @PostMapping("/alerts/{id}/escalate")
    public String escalate(@PathVariable("id") long id, Authentication auth, RedirectAttributes ra) {
        if (risks.escalate(id)) {
            audit.log(auth.getName(), "ALERT_ESCALATED", "alert #" + id, "escalated to IT Security Manager");
            ra.addFlashAttribute("ok", "Escalated to the IT Security Manager.");
        } else {
            ra.addFlashAttribute("err", "Start the investigation first; only an alert under investigation can be escalated.");
        }
        return "redirect:/alerts/" + id;
    }

    @PostMapping("/alerts/{id}/close")
    public String close(@PathVariable("id") long id, @RequestParam("resolution") String resolution, @RequestParam(name = "note", required = false) String note,
            Authentication auth, RedirectAttributes ra) {
        String res = View.oneOf(resolution, "FALSE_POSITIVE", "CONFIRMED_RISK", "DUPLICATE", "NOT_RELEVANT");
        Map<String, Object> a = risks.alert(id);
        if (res == null || a == null) {
            ra.addFlashAttribute("err", "Choose a result before closing.");
            return "redirect:/alerts/" + id;
        }
        boolean investigated = "IN_PROGRESS".equals(a.get("status"));
        if (!investigated && (res.equals("FALSE_POSITIVE") || res.equals("CONFIRMED_RISK"))) {
            ra.addFlashAttribute("err", "Start the investigation before recording False positive or Confirmed risk.");
            return "redirect:/alerts/" + id;
        }
        String n = note == null ? "" : note.trim();
        if (n.isEmpty() || n.length() > 4000) {
            ra.addFlashAttribute("err", "Write a short closing note (what you checked and why). It is kept as audit evidence.");
            return "redirect:/alerts/" + id;
        }
        if (risks.close(id, res, auth.getName())) {
            risks.addNote(id, auth.getName(), "[Closed: " + res + "] " + n);
            audit.log(auth.getName(), "ALERT_STATUS", "alert #" + id, a.get("status") + " -> CLOSED (" + res + ")");
            ra.addFlashAttribute("ok", "Alert closed as " + res.replace('_', ' ').toLowerCase() + ".");
        } else {
            ra.addFlashAttribute("err", "This alert was already closed.");
        }
        return "redirect:/alerts/" + id;
    }
}
