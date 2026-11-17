package com.ilm.web.service;

import com.ilm.web.dao.AdminDao;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Writes the audit trail of this application (NFR-S03, BR-08). */
@Service
public class AuditService {
    private static final Logger LOG = Logger.getLogger(AuditService.class.getName());
    private final AdminDao dao;

    public AuditService(AdminDao dao) { this.dao = dao; }

    public void log(String actor, String action, String target, String detail) {
        String ip = null;
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) ip = sra.getRequest().getRemoteAddr();
        log(actor, action, target, detail, ip);
    }

    public void log(String actor, String action, String target, String detail, String ip) {
        try {
            String d = detail != null && detail.length() > 4000 ? detail.substring(0, 4000) : detail;
            String t = target != null && target.length() > 200 ? target.substring(0, 200) : target;
            dao.audit(actor == null ? "system" : actor, action, t, d, ip);
        } catch (RuntimeException e) {
            // The audit trail must not hide the original action's result, but a failure here is serious.
            LOG.log(Level.SEVERE, "AUDIT TRAIL WRITE FAILED for " + action + " by " + actor, e);
        }
    }
}
