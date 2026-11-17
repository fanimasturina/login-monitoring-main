package com.ilm.web.service;

import com.ilm.core.parse.AtiParser;
import com.ilm.core.parse.ParseResult;
import com.ilm.core.parse.WebLogParser;
import com.ilm.web.config.AppProps;
import com.ilm.web.dao.AdminDao;
import java.io.BufferedReader;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

/**
 * Real-time collection. Active only when ingest.mode=realtime in ilm.properties; with
 * ingest.mode=upload (default) nothing runs here and data comes from the Upload page.
 *
 * Two optional sources, polled every realtime.poll.seconds:
 *  1. ATI database: realtime.ati.jndi + realtime.ati.sql (rows newer than the last Aua Id)
 *  2. Application log files on this server: realtime.log.files (reads only the new lines)
 */
@Service
public class RealtimeService implements InitializingBean, DisposableBean {
    private static final Logger LOG = Logger.getLogger(RealtimeService.class.getName());
    private static final int MAX_BYTES_PER_POLL = 8 * 1024 * 1024;

    private final AppProps props;
    private final AdminDao adminDao;
    private final IngestService ingest;
    private final AnalysisService analysis;
    private final SettingsService settings;
    private ScheduledExecutorService scheduler;
    private volatile LocalDateTime lastRun;
    private volatile String lastError;

    public RealtimeService(AppProps props, AdminDao adminDao, IngestService ingest, AnalysisService analysis, SettingsService settings) {
        this.props = props;
        this.adminDao = adminDao;
        this.ingest = ingest;
        this.analysis = analysis;
        this.settings = settings;
    }

    public boolean enabled() { return "realtime".equalsIgnoreCase(props.get("ingest.mode", "upload")); }
    public LocalDateTime lastRun() { return lastRun; }
    public String lastError() { return lastError; }

    @Override
    public void afterPropertiesSet() {
        if (!enabled()) { LOG.info("ingest.mode=upload: real-time collection is off"); return; }
        int seconds = Math.max(5, props.i("realtime.poll.seconds", 30));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ilm-realtime");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::poll, 20, seconds, TimeUnit.SECONDS);
        LOG.info("ingest.mode=realtime: polling every " + seconds + " seconds");
    }

    private void poll() {
        try {
            int n = pollAti() + pollLogs();
            if (n > 0) analysis.analyzePending("realtime");
            lastRun = LocalDateTime.now();
            lastError = null;
        } catch (Throwable t) { // keep the scheduler alive; the next poll retries from the last checkpoint
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            LOG.log(Level.WARNING, "Real-time poll failed: " + t.getMessage(), t);
        }
    }

    private int pollAti() throws Exception {
        String jndi = props.get("realtime.ati.jndi");
        String sql = props.get("realtime.ati.sql");
        if (jndi == null || sql == null) return 0;
        String key = "ATI:" + jndi;
        long last = Math.max(0, adminDao.checkpoint(key));
        String idColumn = props.get("realtime.ati.id.column", "aua_id");
        DataSource ds = (DataSource) new InitialContext().lookup("java:comp/env/" + jndi);
        List<List<Object>> rows = new ArrayList<>();
        long maxId = last;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            c.setReadOnly(true);
            ps.setLong(1, last);
            ps.setMaxRows(props.i("realtime.ati.batch", 5000));
            ps.setQueryTimeout(60);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Object> header = new ArrayList<>();
                int idIdx = -1;
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    header.add(md.getColumnLabel(i));
                    if (md.getColumnLabel(i).equalsIgnoreCase(idColumn)) idIdx = i;
                }
                if (idIdx < 0) throw new IllegalStateException("realtime.ati.sql must return the column " + idColumn);
                rows.add(header);
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.add(rs.getObject(i));
                    rows.add(row);
                    maxId = Math.max(maxId, rs.getLong(idIdx));
                }
            }
        }
        if (rows.size() <= 1) return 0;
        ParseResult pr = AtiParser.parse(rows);
        ingest.store(pr, "REALTIME", "ATI_DB", null, jndi, null, "realtime", key, maxId);
        return pr.events.size();
    }

    private int pollLogs() throws Exception {
        String files = props.get("realtime.log.files");
        if (files == null) return 0;
        int total = 0;
        for (String spec : files.split(",")) {
            String[] p = spec.trim().split("\\|");
            if (p[0].isBlank()) continue;
            Path path = Path.of(p[0].trim());
            String app = p.length > 1 ? p[1].trim() : props.get("realtime.log.app", "APP-UNKNOWN");
            if (!Files.isReadable(path)) { LOG.warning("Real-time log not readable: " + path); continue; }
            String key = "LOG:" + path;
            long pos = Math.max(0, adminDao.checkpoint(key));
            long size = Files.size(path);
            if (size < pos) pos = 0; // file was rotated
            if (size == pos) continue;
            int len = (int) Math.min(size - pos, MAX_BYTES_PER_POLL);
            byte[] buf = new byte[len];
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(pos);
                raf.readFully(buf);
            }
            int end = len;
            while (end > 0 && buf[end - 1] != '\n') end--; // only complete lines; the rest waits for the next poll
            if (end == 0) continue;
            String text = new String(buf, 0, end, StandardCharsets.UTF_8);
            ParseResult pr = WebLogParser.parse(new BufferedReader(new StringReader(text)), app, settings.unknownResultLocations());
            settings.addUnknownResultLocations(pr.alwaysNullLocations);
            ingest.store(pr, "REALTIME", "APP_LOG", app, path.getFileName().toString(), null, "realtime", key, pos + end);
            total += pr.events.size();
        }
        return total;
    }

    @Override
    public void destroy() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
