package com.ilm.core.tools;

import com.ilm.core.detect.DetectionEngine;
import com.ilm.core.detect.RiskResult;
import com.ilm.core.detect.Settings;
import com.ilm.core.ml.IsolationForest;
import com.ilm.core.model.LoginEvent;
import com.ilm.core.parse.AtiParser;
import com.ilm.core.parse.ParseResult;
import com.ilm.core.parse.TableReader;
import com.ilm.core.parse.WebLogParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the whole pipeline on files without a database or Tomcat:
 *   java com.ilm.core.tools.EngineSelfTest file1 [file2 ...]
 * .xlsx/.csv files are read as ATI exports, anything else as web-user / web-security logs.
 * Use it to check parsing and scoring on new data before uploading (also run by "ant test").
 */
public final class EngineSelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.out.println("Usage: EngineSelfTest <file> [<file> ...] [--top=N] [--app=CODE]"); return; }
        List<LoginEvent> all = new ArrayList<>();
        int top = 15;
        String app = "APP-DEMO";
        for (String a : args) {
            if (a.startsWith("--top=")) top = Integer.parseInt(a.substring(6));
            if (a.startsWith("--app=")) app = a.substring(6);
        }
        for (String a : args) {
            if (a.startsWith("--")) continue;
            Path p = Path.of(a);
            ParseResult pr;
            String lower = a.toLowerCase();
            if (lower.endsWith(".xlsx")) {
                try (InputStream in = Files.newInputStream(p)) { pr = AtiParser.parse(TableReader.readXlsx(in)); }
            } else if (lower.endsWith(".csv")) {
                try (BufferedReader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { pr = AtiParser.parse(TableReader.readCsv(in)); }
            } else {
                try (BufferedReader in = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { pr = WebLogParser.parse(in, app, Set.of()); }
            }
            System.out.printf("%-60s events=%d errors=%d skipped=%d alwaysNull=%s%n", p.getFileName(), pr.events.size(),
                    pr.errors.size(), pr.skipped, pr.alwaysNullLocations);
            pr.errors.stream().limit(3).forEach(e -> System.out.println("   error line " + e.lineNo() + ": " + e.message()));
            all.addAll(pr.events);
        }
        all.sort(Comparator.comparing((LoginEvent e) -> e.eventTime));
        Settings cfg = new Settings();
        long logins = all.stream().filter(LoginEvent::isLogin).count();
        IsolationForest model = null;
        if (logins >= cfg.i("ml.min.samples")) {
            long t0 = System.nanoTime();
            model = DetectionEngine.train(all, cfg, 42);
            model = IsolationForest.deserialize(model.serialize()); // also proves save/load works
            System.out.printf("Model trained on %d logins in %d ms (%d trees)%n", logins, (System.nanoTime() - t0) / 1_000_000, model.treeCount());
        } else {
            System.out.println("Only " + logins + " logins: rules only, no model (need " + cfg.i("ml.min.samples") + ")");
        }
        DetectionEngine eng = new DetectionEngine(cfg, model);
        Map<String, Integer> levels = new TreeMap<>();
        Map<String, Integer> codes = new TreeMap<>();
        List<Object[]> scored = new ArrayList<>();
        long t0 = System.nanoTime();
        for (LoginEvent e : all) {
            RiskResult r = eng.process(e);
            if (r == null) continue;
            levels.merge(r.riskLevel, 1, Integer::sum);
            r.factors.forEach(f -> { if (f.points() > 0) codes.merge(f.code(), 1, Integer::sum); });
            scored.add(new Object[] {e, r});
        }
        double usPerEvent = (System.nanoTime() - t0) / 1000.0 / Math.max(1, scored.size());
        System.out.printf("Scored %d events, %.1f microseconds per event%n", scored.size(), usPerEvent);
        System.out.println("Risk levels : " + levels);
        System.out.println("Factor hits : " + codes);
        scored.sort((a, b) -> Integer.compare(((RiskResult) b[1]).riskScore, ((RiskResult) a[1]).riskScore));
        System.out.println("Top " + top + " events:");
        for (Object[] o : scored.subList(0, Math.min(top, scored.size()))) {
            LoginEvent e = (LoginEvent) o[0];
            RiskResult r = (RiskResult) o[1];
            System.out.printf(" %3d %-8s %s %-22s %-8s %s%n", r.riskScore, r.riskLevel, e.eventTime, e.userId, e.result,
                    r.anomalyScore == null ? "" : String.format("ml=%.2f", r.anomalyScore));
            r.factors.stream().filter(f -> f.points() > 0).forEach(f -> System.out.println("        +" + f.points() + " " + f.text()));
        }
    }
}
