package com.ilm.web.service;

import com.ilm.core.detect.DetectionEngine;
import com.ilm.core.detect.DetectionState.UserHist;
import com.ilm.core.detect.RiskResult;
import com.ilm.core.detect.Settings;
import com.ilm.core.ml.IsolationForest;
import com.ilm.core.model.LoginEvent;
import com.ilm.web.dao.AdminDao;
import com.ilm.web.dao.Db;
import com.ilm.web.dao.EventDao;
import com.ilm.web.dao.RiskDao;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

/**
 * Runs Detect -> Score -> Explain -> Alert on stored events.
 * One engine instance keeps the behavioural history in memory; all methods are synchronized
 * because events must be processed one at a time, in time order.
 */
@Service
public class AnalysisService {
    private static final Logger LOG = Logger.getLogger(AnalysisService.class.getName());
    private static final int CHUNK = 500;
    private static final int MAX_TRAINING_EVENTS = 200_000;

    public record Summary(int scored, int newAlerts, Integer modelVersion, boolean trained, boolean fullReplay) {}

    private final Db db;
    private final EventDao eventDao;
    private final RiskDao riskDao;
    private final AdminDao adminDao;
    private final SettingsService settings;
    private final MailService mail;
    private final AuditService audit;

    private DetectionEngine engine;
    private int engineSettingsVersion = -1;
    private Integer engineModelVersion;
    private IsolationForest cachedModel;
    private Integer cachedModelVersion;

    public AnalysisService(Db db, EventDao eventDao, RiskDao riskDao, AdminDao adminDao, SettingsService settings, MailService mail, AuditService audit) {
        this.db = db;
        this.eventDao = eventDao;
        this.riskDao = riskDao;
        this.adminDao = adminDao;
        this.settings = settings;
        this.mail = mail;
        this.audit = audit;
    }

    public synchronized Summary analyzePending(String actor) { return run(false, actor); }

    /** Score every stored event again, e.g. after settings changed (admin action). */
    public synchronized Summary rescoreAll(String actor) {
        Summary s = run(true, actor);
        audit.log(actor, "RESCORE_ALL", "login_event", s.scored() + " events scored, " + s.newAlerts() + " new alerts, model v" + s.modelVersion());
        return s;
    }

    /** Train a new model version from stored history and make it active (admin action). */
    public synchronized int train(String actor) {
        int v = trainInternal(actor, settings.current());
        engine = null; // next run replays history with the new model
        return v;
    }

    private int trainInternal(String actor, Settings cfg) {
        List<LoginEvent> events = new ArrayList<>();
        db.stream("SELECT " + EventDao.EVENT_COLUMNS + " FROM login_event ORDER BY event_time DESC, id DESC LIMIT ?",
                rs -> events.add(EventDao.map(rs)), MAX_TRAINING_EVENTS);
        Collections.reverse(events);
        long logins = events.stream().filter(LoginEvent::isLogin).count();
        if (logins < 50) throw new IllegalStateException("Need at least 50 login events to train a model; found " + logins);
        IsolationForest model = DetectionEngine.train(events, cfg, System.nanoTime());
        int version = adminDao.saveModel("IsolationForest", actor, (int) logins, model.treeCount(), model.sampleSize(),
                String.join(",", DetectionEngine.FEATURES), model.serialize());
        cachedModel = model;
        cachedModelVersion = version;
        audit.log(actor, "MODEL_TRAINED", "ml_model v" + version, logins + " login events, " + model.treeCount() + " trees");
        LOG.info("Model v" + version + " trained on " + logins + " login events");
        return version;
    }

    private IsolationForest loadModel(Map<String, Object> row) {
        Integer v = (Integer) row.get("version");
        if (!Objects.equals(v, cachedModelVersion)) {
            cachedModel = IsolationForest.deserialize((String) row.get("model_data"));
            cachedModelVersion = v;
        }
        return cachedModel;
    }

    private Summary run(boolean all, String actor) {
        Settings cfg = settings.current();
        int cfgVersion = settings.version();
        boolean trained = false;
        Map<String, Object> modelRow = adminDao.activeModel();
        if (modelRow == null && cfg.bool("ml.enabled") && eventDao.loginCount() >= cfg.i("ml.min.samples")) {
            trainInternal("system", cfg);
            trained = true;
            modelRow = adminDao.activeModel();
        }
        Integer modelVersion = modelRow == null ? null : (Integer) modelRow.get("version");
        if (!all && !trained && eventDao.pendingCount() == 0) return new Summary(0, 0, modelVersion, false, false);

        // First model: score earlier (rules-only) events again so every event has an anomaly score.
        boolean scoreAll = all || trained;
        boolean replay = scoreAll || engine == null || engineSettingsVersion != cfgVersion || !Objects.equals(modelVersion, engineModelVersion);
        if (!replay && engine.state().lastTime() != null) {
            Map<String, Object> min = db.one("SELECT min(event_time) AS t FROM login_event WHERE status='NORMALIZED'");
            LocalDateTime first = min == null || min.get("t") == null ? null : ((Timestamp) min.get("t")).toLocalDateTime();
            // Older events arrived after newer ones were scored: history must be rebuilt in time order.
            if (first != null && first.isBefore(engine.state().lastTime())) replay = true;
        }
        if (replay) engine = new DetectionEngine(cfg, modelRow == null ? null : loadModel(modelRow));

        final DetectionEngine eng = engine;
        final boolean everything = scoreAll;
        final int threshold = cfg.i("alert.threshold");
        final Integer mv = eng.hasModel() ? modelVersion : null;
        List<Object[]> buffer = new ArrayList<>(CHUNK);
        List<Long> newAlerts = new ArrayList<>();
        Set<String> touched = new HashSet<>();
        int[] scored = {0};
        String sql = "SELECT " + EventDao.EVENT_COLUMNS + " FROM login_event"
                + (replay ? "" : " WHERE status='NORMALIZED'") + " ORDER BY event_time, id";
        db.stream(sql, rs -> {
            LoginEvent e = EventDao.map(rs);
            if (everything || "NORMALIZED".equals(rs.getString("status"))) {
                RiskResult r = eng.process(e);
                buffer.add(new Object[] {e, r});
                if (e.isLogin()) touched.add(e.userId);
                scored[0]++;
                if (buffer.size() >= CHUNK) flush(buffer, threshold, mv, newAlerts);
            } else {
                eng.observe(e);
            }
        });
        flush(buffer, threshold, mv, newAlerts);
        if (!touched.isEmpty()) {
            db.tx(c -> {
                for (String u : touched) {
                    UserHist h = eng.state().users().get(u);
                    if (h != null) riskDao.upsertProfile(c, u, h);
                }
                return null;
            });
        }
        engineSettingsVersion = cfgVersion;
        engineModelVersion = modelVersion;
        mail.notifyNewAlerts(newAlerts);
        LOG.info("Analysis by " + actor + ": " + scored[0] + " events scored, " + newAlerts.size() + " new alerts, replay=" + replay);
        return new Summary(scored[0], newAlerts.size(), modelVersion, trained, replay);
    }

    private void flush(List<Object[]> buffer, int threshold, Integer modelVersion, List<Long> newAlerts) {
        if (buffer.isEmpty()) return;
        List<Long> created = db.tx(c -> {
            List<Long> ids = new ArrayList<>();
            for (Object[] o : buffer) {
                LoginEvent e = (LoginEvent) o[0];
                RiskResult r = (RiskResult) o[1];
                if (r != null) {
                    riskDao.saveRisk(c, e.id, r, modelVersion);
                    if (r.riskScore >= threshold && !riskDao.hasAlert(c, e.id)) {
                        Long id = riskDao.attachOrCreateAlert(c, e, r);
                        if (id != null) ids.add(id);
                    }
                }
                riskDao.markScored(c, e.id);
            }
            return ids;
        });
        newAlerts.addAll(created);
        buffer.clear();
    }
}
