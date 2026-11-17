package com.ilm.core.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes made-up sample files in the same three formats as the real sources:
 *   web-user.log, web-security.log, user_activity.csv
 * All user IDs are invented (demo.*), so the files are safe to share and to demo with.
 * Usage: DemoDataGenerator [outputDir] [days] [endDate yyyy-MM-dd]
 */
public final class DemoDataGenerator {
    private static final DateTimeFormatter LOG = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
    private static final DateTimeFormatter CSV = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] FIRST = {"andi", "budi", "citra", "dewi", "eko", "fitri", "gilang", "hana", "indra", "joko", "kartika",
        "lina", "made", "nanda", "oki", "putri", "rizky", "sari", "tono", "utami", "vina", "wahyu", "yudi", "zahra"};

    private record Line(LocalDateTime t, String text) {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "demo-data");
        int days = args.length > 1 ? Integer.parseInt(args[1]) : 90;
        LocalDate end = args.length > 2 ? LocalDate.parse(args[2]) : LocalDate.now().minusDays(1);
        Random rnd = new Random(20261116L);
        Files.createDirectories(dir);
        List<Line> user = new ArrayList<>(), sec = new ArrayList<>(), ati = new ArrayList<>();
        long aua = 500000;
        String[] users = new String[FIRST.length];
        for (int i = 0; i < FIRST.length; i++) users[i] = "demo." + FIRST[i];
        for (int d = days - 1; d >= 0; d--) {
            LocalDate day = end.minusDays(d);
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            for (int u = 0; u < users.length; u++) {
                if (weekend || rnd.nextDouble() < 0.25) continue; // not every user works every day
                int logins = 1 + rnd.nextInt(3);
                for (int k = 0; k < logins; k++) {
                    int startHour = 8 + (u % 3); // each user has a personal rhythm
                    LocalDateTime t = day.atTime(startHour + k * 3 + rnd.nextInt(2), rnd.nextInt(60), rnd.nextInt(60), rnd.nextInt(1000) * 1_000_000);
                    if (rnd.nextDouble() < 0.06) { // expired session first
                        sec.add(new Line(t.minusSeconds(6), secLine(t.minusSeconds(6), rnd)));
                    }
                    if (rnd.nextDouble() < 0.08) { // one mistyped password
                        user.add(new Line(t.minusSeconds(4), userLine(t.minusSeconds(4), users[u], null, rnd)));
                    }
                    user.add(new Line(t, userLine(t, users[u], name(FIRST[u]), rnd)));
                    if (u % 2 == 0) ati.add(new Line(t.plusSeconds(1), atiLine(++aua, u % 4 == 0 ? "APP-DEMO-B" : "APP-DEMO-A", users[u], name(FIRST[u]), t.plusSeconds(1))));
                }
            }
            if (!weekend && rnd.nextDouble() < 0.5) {
                LocalDateTime t = day.atTime(9 + rnd.nextInt(8), rnd.nextInt(60), rnd.nextInt(60));
                user.add(new Line(t, userLine(t, "demo.superadm", "demo.superadm", rnd)));
            }
        }
        // ---- planted anomalies, so the dashboard has something to show ----
        LocalDate d1 = end.minusDays(2);
        LocalDateTime night = d1.atTime(1, 12, 40);
        for (int i = 0; i < 6; i++) user.add(new Line(night.plusSeconds(i * 2L), userLine(night.plusSeconds(i * 2L), "demo.superadm", null, rnd)));
        user.add(new Line(night.plusSeconds(15), userLine(night.plusSeconds(15), "demo.superadm", "demo.superadm", rnd)));
        LocalDateTime enumT = end.minusDays(5).atTime(13, 30, 5);
        String[] guesses = {"demo.admin", "administrator", "demo.root", "demo.test", "demo.guest", "demo.support"};
        for (int i = 0; i < guesses.length; i++) user.add(new Line(enumT.plusSeconds(i * 7L), userLine(enumT.plusSeconds(i * 7L), guesses[i], null, rnd)));
        LocalDateTime burst = end.minusDays(8).atTime(10, 56, 0);
        for (int i = 0; i < 14; i++) sec.add(new Line(burst.plusNanos(i * 900_000_000L), secLine(burst.plusNanos(i * 900_000_000L), rnd)));
        LocalDate sunday = end.minusDays(1);
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) sunday = sunday.minusDays(1);
        LocalDateTime sun = sunday.atTime(20, 41, 12);
        user.add(new Line(sun, userLine(sun, users[3], name(FIRST[3]), rnd)));
        ati.add(new Line(sun.plusSeconds(40), atiLine(++aua, "APP-DEMO-C", users[3], name(FIRST[3]), sun.plusSeconds(40))));
        LocalDateTime dbl = end.minusDays(3).atTime(9, 10, 40);
        for (int i = 0; i < 8; i++) user.add(new Line(dbl.plusNanos(i * 90_000_000L), userLine(dbl.plusNanos(i * 90_000_000L), users[7], name(FIRST[7]), rnd)));

        write(dir.resolve("web-user.log"), user, null);
        write(dir.resolve("web-security.log"), sec, null);
        write(dir.resolve("user_activity.csv"), ati, "No.,Aua Id,App Code,User Id,Action Code,Menu,Description,Parameters,Date Activity,Endpoint");
        System.out.println("Demo files written to " + dir.toAbsolutePath() + " (" + user.size() + " logins, " + sec.size()
                + " security events, " + ati.size() + " ATI rows)");
    }

    private static String name(String first) { return Character.toUpperCase(first.charAt(0)) + first.substring(1) + " Demo"; }

    private static String ip(Random rnd) { return rnd.nextBoolean() ? "127.0.0.1" : "0:0:0:0:0:0:0:1"; }

    private static String userLine(LocalDateTime t, String user, String name, Random rnd) {
        String ip = ip(rnd);
        return t.format(LOG) + " [https-jsse-nio-8443-exec-" + (1 + rnd.nextInt(10)) + "] LoginController:doLogin:95 - Login, IP : " + ip
                + ", PC Name : " + ip + ", User ID : " + user + ", Name : " + name;
    }

    private static String secLine(LocalDateTime t, Random rnd) {
        String ip = ip(rnd);
        return t.format(LOG) + " [https-jsse-nio-8443-exec-" + (1 + rnd.nextInt(10)) + "] WebAuthFilter:doFilter:81 - ---> Unauthorized access attempt from IP : "
                + ip + ", PC Name : " + ip;
    }

    private static String atiLine(long aua, String app, String user, String name, LocalDateTime t) {
        return aua + "," + app + "," + user + ",login,login,user " + name + " logged in,\"\"\"\"\"\"," + t.format(CSV) + ",/do-login";
    }

    private static void write(Path file, List<Line> lines, String header) throws IOException {
        lines.sort((a, b) -> a.t().compareTo(b.t()));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (header != null) w.println(header);
            int no = 0;
            for (Line l : lines) w.println(header != null ? (++no) + "," + l.text() : l.text());
        }
    }
}
