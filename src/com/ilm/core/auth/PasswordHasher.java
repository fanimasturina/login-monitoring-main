package com.ilm.core.auth;

import java.io.Console;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-HMAC-SHA256 password hashing for the optional local (non-AD) accounts (NFR-S05).
 * Run "ant hash-password" to create a hash; the password is never stored in plain text.
 */
public final class PasswordHasher {
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;

    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] dk = derive(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "pbkdf2$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    public static boolean verify(char[] password, String stored) {
        if (stored == null || password == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
        try {
            int it = Integer.parseInt(p[1]);
            if (it < 10_000 || it > 5_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, it));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("No interactive console. Run from a terminal: java -cp build/classes com.ilm.core.auth.PasswordHasher");
            System.exit(1);
        }
        char[] a = console.readPassword("New password (min 12 characters): ");
        char[] b = console.readPassword("Repeat password: ");
        if (a == null || a.length < 12) { System.err.println("Password must be at least 12 characters."); System.exit(1); }
        if (!java.util.Arrays.equals(a, b)) { System.err.println("Passwords do not match."); System.exit(1); }
        System.out.println(hash(a));
    }
}
