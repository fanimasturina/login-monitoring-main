package com.ilm.web.service;

import com.ilm.core.model.LoginEvent;
import com.ilm.core.parse.AtiParser;
import com.ilm.core.parse.ParseResult;
import com.ilm.core.parse.TableReader;
import com.ilm.core.parse.WebLogParser;
import com.ilm.core.util.Util;
import com.ilm.web.dao.AdminDao;
import com.ilm.web.dao.Db;
import com.ilm.web.dao.EventDao;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Collect -> validate -> standardize -> store (FR-01..FR-10), then hand over to analysis. */
@Service
public class IngestService {
    private final Db db;
    private final EventDao eventDao;
    private final AdminDao adminDao;
    private final SettingsService settings;
    private final AnalysisService analysis;
    private final AuditService audit;

    public IngestService(Db db, EventDao eventDao, AdminDao adminDao, SettingsService settings, AnalysisService analysis, AuditService audit) {
        this.db = db;
        this.eventDao = eventDao;
        this.adminDao = adminDao;
        this.settings = settings;
        this.analysis = analysis;
        this.audit = audit;
    }

    /** Parse an uploaded file. .xlsx and .csv are ATI activity exports; other files are application logs. */
    public Map<String, Object> ingestFile(byte[] data, String fileName, String appCode, String actor) throws IOException {
        String name = fileName == null ? "upload" : fileName.replaceAll("[^A-Za-z0-9._\\- ]", "_");
        if (name.length() > 200) name = name.substring(name.length() - 200);
        String lower = name.toLowerCase(Locale.ROOT);
        ParseResult pr;
        String kind;
        if (lower.endsWith(".xlsx")) {
            kind = "ATI_XLSX";
            pr = AtiParser.parse(TableReader.readXlsx(new ByteArrayInputStream(data)));
        } else if (lower.endsWith(".csv")) {
            kind = "ATI_CSV";
            pr = AtiParser.parse(TableReader.readCsv(reader(data)));
        } else {
            kind = "APP_LOG";
            if (Util.blank(appCode)) throw new IllegalArgumentException("Choose the application code for this log file.");
            pr = WebLogParser.parse(reader(data), appCode, settings.unknownResultLocations());
            settings.addUnknownResultLocations(pr.alwaysNullLocations);
        }
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Map<String, Object> out = store(pr, "UPLOAD", kind, kind.equals("APP_LOG") ? appCode : null, name, sha, actor, null, -1);
        audit.log(actor, "UPLOAD", name, kind + ": accepted " + out.get("accepted") + ", duplicates " + out.get("duplicates")
                + ", rejected " + out.get("rejected") + ", sha256 " + sha);
        AnalysisService.Summary s = analysis.analyzePending(actor);
        out.put("scored", s.scored());
        out.put("newAlerts", s.newAlerts());
        out.put("trained", s.trained());
        out.put("modelVersion", s.modelVersion());
        return out;
    }

    /** Store parsed events in one transaction. Also saves the real-time checkpoint in the same transaction. */
    public Map<String, Object> store(ParseResult pr, String origin, String kind, String appCode, String name, String sha, String actor,
            String checkpointKey, long checkpointPos) {
        return db.tx(c -> {
            long batchId = eventDao.insertBatch(c, origin, kind, appCode, name, sha, actor);
            int accepted = 0, dup = 0;
            for (LoginEvent e : pr.events) {
                Long id = eventDao.insertEvent(c, batchId, e);
                if (id == null) dup++; else accepted++;
            }
            for (ParseResult.ParseError er : pr.errors) eventDao.insertError(c, batchId, er.lineNo(), er.raw(), Util.cut(er.message(), 300));
            eventDao.finishBatch(c, batchId, accepted, dup, pr.errors.size(), pr.skipped);
            if (checkpointKey != null) adminDao.saveCheckpoint(c, checkpointKey, checkpointPos);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("batchId", batchId);
            out.put("kind", kind);
            out.put("fileName", name);
            out.put("accepted", accepted);
            out.put("duplicates", dup);
            out.put("rejected", pr.errors.size());
            out.put("skipped", pr.skipped);
            out.put("unknownLocations", String.join(", ", pr.alwaysNullLocations));
            return out;
        });
    }

    private static BufferedReader reader(byte[] data) {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
    }
}
