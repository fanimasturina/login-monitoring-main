package com.ilm.web.controller;

import com.ilm.core.auth.AdAuthenticator;
import com.ilm.core.auth.PasswordHasher;
import com.ilm.core.detect.Settings;
import com.ilm.web.config.AppProps;
import com.ilm.web.dao.AdminDao;
import com.ilm.web.dao.EventDao;
import com.ilm.web.service.AnalysisService;
import com.ilm.web.service.AuditService;
import com.ilm.web.service.MailService;
import com.ilm.web.service.RealtimeService;
import com.ilm.web.service.SettingsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Administration: thresholds and rules (UC-03, UC-11), users (UC-12), model versions, audit trail. */
@Controller
public class AdminController {
    private final AdminDao dao;
    private final EventDao events;
    private final SettingsService settings;
    private final AnalysisService analysis;
    private final AuditService audit;
    private final AppProps props;
    private final RealtimeService realtime;
    private final MailService mail;

    public AdminController(AdminDao dao, EventDao events, SettingsService settings, AnalysisService analysis, AuditService audit, AppProps props,
            RealtimeService realtime, MailService mail) {
        this.dao = dao;
        this.events = events;
        this.settings = settings;
        this.analysis = analysis;
        this.audit = audit;
        this.props = props;
        this.realtime = realtime;
        this.mail = mail;
    }

    // ---------------- settings ----------------
    @GetMapping("/admin/settings")
    public String settings(Model model) {
        Settings current = settings.current();
        List<Map<String, Object>> rows = new ArrayList<>();
        settings.definitions().forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k);
            m.put("value", current.get(k));
            m.put("def", v[0]);
            m.put("desc", v[1]);
            m.put("group", k.startsWith("rule.") ? "Rule points" : k.startsWith("ml.") ? "Machine learning" : "Thresholds and working hours");
            rows.add(m);
        });
        model.addAttribute("rows", rows);
        return "admin/settings";
    }

    @PostMapping("/admin/settings")
    public String saveSetting(@RequestParam("key") String key, @RequestParam("value") String value, Authentication auth, RedirectAttributes ra) {
        String error = Settings.validate(key, value);
        if (error != null) {
            ra.addFlashAttribute("err", key + ": " + error + ".");
            return "redirect:/admin/settings";
        }
        String old = settings.current().get(key);
        settings.save(key, value, auth.getName());
        audit.log(auth.getName(), "SETTING_CHANGED", key, old + " -> " + value.trim());
        ra.addFlashAttribute("ok", key + " saved. It applies to events scored from now on; use Model > Score all events again to apply it to history.");
        return "redirect:/admin/settings";
    }

    // ---------------- users ----------------
    @GetMapping("/admin/users")
    public String users(Model model) {
        model.addAttribute("rows", dao.users());
        model.addAttribute("localEnabled", props.bool("auth.local.enabled", false));
        return "admin/users";
    }

    @PostMapping("/admin/users")
    public String saveUser(@RequestParam("userId") String userId, @RequestParam("role") String role,
            @RequestParam(name = "active", required = false) String active, @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "password", required = false) String password, Authentication auth, RedirectAttributes ra) {
        String id = AdAuthenticator.normalize(userId);
        String r = View.oneOf(role, "ANALYST", "ADMIN", "MANAGER", "AUDITOR");
        boolean isActive = active != null;
        if (id == null || r == null) {
            ra.addFlashAttribute("err", "Enter a valid user ID (letters, numbers, dot, dash) and choose a role.");
            return "redirect:/admin/users";
        }
        Map<String, Object> existing = dao.user(id);
        String src = existing != null ? (String) existing.get("auth_source") : ("LOCAL".equals(source) ? "LOCAL" : "AD");
        String hash = null;
        if ("LOCAL".equals(src)) {
            if (!props.bool("auth.local.enabled", false)) {
                ra.addFlashAttribute("err", "Local accounts are switched off (auth.local.enabled=false). Use an Active Directory user ID.");
                return "redirect:/admin/users";
            }
            if (password != null && !password.isEmpty()) {
                if (password.length() < 12) {
                    ra.addFlashAttribute("err", "Local passwords need at least 12 characters.");
                    return "redirect:/admin/users";
                }
                hash = PasswordHasher.hash(password.toCharArray());
            } else if (existing == null) {
                ra.addFlashAttribute("err", "A new local account needs a password.");
                return "redirect:/admin/users";
            }
        }
        boolean wasAdmin = existing != null && "ADMIN".equals(existing.get("role")) && Boolean.TRUE.equals(existing.get("active"));
        if (wasAdmin && (!"ADMIN".equals(r) || !isActive) && dao.activeAdmins() <= 1) {
            ra.addFlashAttribute("err", "This is the last active administrator. Add another administrator first.");
            return "redirect:/admin/users";
        }
        if (id.equals(auth.getName()) && (!isActive || !"ADMIN".equals(r))) {
            ra.addFlashAttribute("err", "You cannot remove your own administrator access. Ask another administrator.");
            return "redirect:/admin/users";
        }
        dao.saveUser(id, null, null, r, src, hash, isActive, auth.getName());
        audit.log(auth.getName(), existing == null ? "USER_ADDED" : "USER_CHANGED", id, "role " + r + ", " + (isActive ? "active" : "inactive") + ", " + src
                + (hash != null ? ", password set" : ""));
        ra.addFlashAttribute("ok", "User " + id + " saved as " + r + ".");
        return "redirect:/admin/users";
    }

    // ---------------- model ----------------
    @GetMapping("/admin/model")
    public String model(Model model) {
        model.addAttribute("models", dao.models());
        model.addAttribute("loginCount", events.loginCount());
        model.addAttribute("pending", events.pendingCount());
        model.addAttribute("minSamples", settings.current().i("ml.min.samples"));
        model.addAttribute("ingestMode", realtime.enabled() ? "realtime" : "upload");
        model.addAttribute("realtimeLastRun", realtime.lastRun());
        model.addAttribute("realtimeError", realtime.lastError());
        model.addAttribute("mailOn", mail.enabled());
        return "admin/model";
    }

    @PostMapping("/admin/model/train")
    public String train(Authentication auth, RedirectAttributes ra) {
        try {
            int v = analysis.train(auth.getName());
            AnalysisService.Summary s = analysis.rescoreAll(auth.getName());
            ra.addFlashAttribute("ok", "Model version " + v + " trained and active. " + s.scored() + " events scored again, " + s.newAlerts() + " new alerts.");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/model";
    }

    @PostMapping("/admin/model/rescore")
    public String rescore(Authentication auth, RedirectAttributes ra) {
        AnalysisService.Summary s = analysis.rescoreAll(auth.getName());
        ra.addFlashAttribute("ok", s.scored() + " events scored again with the current settings, " + s.newAlerts() + " new alerts.");
        return "redirect:/admin/model";
    }

    // ---------------- audit trail ----------------
    @GetMapping("/admin/audit")
    public String auditTrail(@RequestParam(name = "actor", required = false) String actor, @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page", required = false) Integer page, Model model) {
        String a = View.clean(actor), ac = View.clean(action);
        long total = dao.auditCount(a, ac);
        int p = Math.min(View.page(page), View.pages(total));
        model.addAttribute("rows", dao.auditRows(a, ac, View.PAGE_SIZE, (p - 1) * View.PAGE_SIZE));
        model.addAttribute("total", total);
        model.addAttribute("page", p);
        model.addAttribute("pages", View.pages(total));
        model.addAttribute("actor", a == null ? "" : a);
        model.addAttribute("action", ac == null ? "" : ac);
        return "admin/audit";
    }
}
