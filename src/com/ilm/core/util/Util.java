package com.ilm.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Util {
    private Util() {}

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    /** LOOPBACK means the app saw a proxy or itself, not the real client (constraint C2). */
    public static String ipClass(String ip) {
        if (blank(ip)) return "NONE";
        String v = ip.trim().toLowerCase();
        if (v.equals("127.0.0.1") || v.equals("::1") || v.equals("0:0:0:0:0:0:0:1") || v.equals("localhost")) return "LOOPBACK";
        if (v.startsWith("10.") || v.startsWith("192.168.") || v.startsWith("169.254.") || v.startsWith("fc") || v.startsWith("fd") || v.startsWith("fe80")) return "PRIVATE";
        if (v.startsWith("172.")) {
            try {
                int second = Integer.parseInt(v.split("\\.")[1]);
                if (second >= 16 && second <= 31) return "PRIVATE";
            } catch (RuntimeException ignored) { /* not an IPv4 address */ }
        }
        return "PUBLIC";
    }

    /** Mask for roles that may not see personal data (NFR-S07, UU PDP). */
    public static String maskUser(String userId) {
        if (blank(userId) || userId.startsWith("(")) return userId;
        if (userId.length() <= 2) return "**";
        return userId.substring(0, 2) + "*".repeat(Math.min(8, userId.length() - 2));
    }

    public static String maskIp(String ip) {
        if (blank(ip)) return ip;
        int dot = ip.lastIndexOf('.');
        if (dot > 0) return ip.substring(0, dot) + ".***";
        int colon = ip.lastIndexOf(':');
        return colon > 0 ? ip.substring(0, colon) + ":****" : "***";
    }
}
