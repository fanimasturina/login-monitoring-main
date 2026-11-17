package com.ilm.core.parse;

import com.ilm.core.model.LoginEvent;
import com.ilm.core.util.Util;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the enterprise application logs web-user.log and web-security.log.
 * The two formats share the same prefix, so one file may even contain both.
 *
 * web-user.log does not say whether a login succeeded. In the sample data "Name : null"
 * appears when the user could not be resolved, so it is read as FAILED. Newer app builds
 * (e.g. doLogin:180) always write null; for those code locations the result is UNKNOWN.
 */
public final class WebLogParser {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}) \\[([^\\]]*)\\] ([\\w$.]+:[\\w$<>]+:\\d+) - (.*)$");
    private static final Pattern LOGIN = Pattern.compile(
            "^Login, IP : (.*?), PC Name : (.*?), User ID : (.*?), Name : (.*)$");
    private static final Pattern UNAUTH = Pattern.compile(
            "^-+> Unauthorized access attempt from IP : (.*?), PC Name : (.*)$");
    /** A location needs this many lines, all with null name, before we call it "always null". */
    private static final int MIN_LINES_FOR_ALWAYS_NULL = 10;
    private static final int MAX_LINE = 4000;

    private WebLogParser() {}

    public static ParseResult parse(BufferedReader in, String appCode, Set<String> knownAlwaysNull) throws IOException {
        ParseResult out = new ParseResult();
        Map<String, int[]> perLocation = new HashMap<>(); // location -> {lines, nullNames}
        String line;
        int no = 0;
        while ((line = in.readLine()) != null) {
            no++;
            if (line.isBlank()) continue;
            if (line.length() > MAX_LINE) { out.error(no, line, "Line too long"); continue; }
            Matcher m = LINE.matcher(line.strip());
            if (!m.matches()) { out.error(no, line, "Unrecognized log line format"); continue; }
            LocalDateTime time;
            try {
                time = LocalDateTime.parse(m.group(1), TS);
            } catch (RuntimeException ex) {
                out.error(no, line, "Invalid timestamp");
                continue;
            }
            String message = m.group(4);
            LoginEvent e = new LoginEvent();
            e.appCode = appCode;
            e.eventTime = time;
            e.threadName = Util.cut(m.group(2), 100);
            e.codeLocation = Util.cut(m.group(3), 150);
            e.sourceRef = String.valueOf(no);
            e.rawText = line;
            Matcher lm = LOGIN.matcher(message);
            Matcher um = UNAUTH.matcher(message);
            if (lm.matches()) {
                String user = lm.group(3).trim().toLowerCase();
                if (user.isEmpty() || "null".equals(user)) { out.error(no, line, "Missing User ID (BR-01)"); continue; }
                String name = lm.group(4).trim();
                boolean nullName = name.isEmpty() || "null".equals(name);
                e.sourceType = LoginEvent.SRC_WEB_USER;
                e.eventType = LoginEvent.TYPE_LOGIN;
                e.userId = Util.cut(user, 100);
                e.displayName = nullName ? null : Util.cut(name, 200);
                e.ipAddress = Util.cut(lm.group(1).trim(), 64);
                e.pcName = Util.cut(lm.group(2).trim(), 100);
                e.result = nullName ? LoginEvent.RES_FAILED : LoginEvent.RES_SUCCESS;
                e.endpoint = e.codeLocation;
                int[] c = perLocation.computeIfAbsent(e.codeLocation, k -> new int[2]);
                c[0]++;
                if (nullName) c[1]++;
            } else if (um.matches()) {
                e.sourceType = LoginEvent.SRC_WEB_SECURITY;
                e.eventType = LoginEvent.TYPE_UNAUTHORIZED;
                e.userId = LoginEvent.ANONYMOUS;
                e.ipAddress = Util.cut(um.group(1).trim(), 64);
                e.pcName = Util.cut(um.group(2).trim(), 100);
                e.result = LoginEvent.RES_FAILED;
                e.endpoint = e.codeLocation;
            } else {
                out.skipped++; // a valid log line that is not an authentication event
                continue;
            }
            e.ipClass = Util.ipClass(e.ipAddress);
            e.eventHash = Util.sha256(e.sourceType + "|" + appCode + "|" + line.strip());
            out.events.add(e);
        }
        for (Map.Entry<String, int[]> en : perLocation.entrySet()) {
            int[] c = en.getValue();
            if (c[0] >= MIN_LINES_FOR_ALWAYS_NULL && c[0] == c[1]) out.alwaysNullLocations.add(en.getKey());
        }
        for (LoginEvent e : out.events) {
            if (!LoginEvent.SRC_WEB_USER.equals(e.sourceType) || e.displayName != null) continue;
            if (out.alwaysNullLocations.contains(e.codeLocation) || (knownAlwaysNull != null && knownAlwaysNull.contains(e.codeLocation))) {
                e.result = LoginEvent.RES_UNKNOWN;
            }
        }
        return out;
    }
}
