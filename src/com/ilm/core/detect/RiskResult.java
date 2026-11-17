package com.ilm.core.detect;

import java.util.ArrayList;
import java.util.List;

/** Output for one login event: anomaly score, risk score, level and the reasons (FR-22..FR-28). */
public final class RiskResult {
    public record Factor(String code, int points, String text) {}

    /** Isolation Forest score 0-1, or null when no model was used. */
    public Double anomalyScore;
    public int mlPoints;
    public int rulePoints;
    public int riskScore;
    public String riskLevel;
    public final List<Factor> factors = new ArrayList<>();

    /** BR-02: 0-25 Low, 26-50 Medium, 51-75 High, 76-100 Critical. */
    public static String level(int score) {
        if (score >= 76) return "CRITICAL";
        if (score >= 51) return "HIGH";
        if (score >= 26) return "MEDIUM";
        return "LOW";
    }

    /** One factor per line: code|points|text. */
    public String factorsText() {
        StringBuilder sb = new StringBuilder();
        for (Factor f : factors) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.code()).append('|').append(f.points()).append('|').append(f.text().replace('\n', ' ').replace('|', '/'));
        }
        return sb.toString();
    }

    public static List<Factor> parseFactors(String text) {
        List<Factor> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length == 3) {
                int pts;
                try { pts = Integer.parseInt(p[1]); } catch (NumberFormatException e) { pts = 0; }
                out.add(new Factor(p[0], pts, p[2]));
            }
        }
        return out;
    }
}
