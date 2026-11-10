package dev.hookrelay.server.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Random tokens and hashing for credentials. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private Secrets() { }

    public static String base62(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = BASE62[RANDOM.nextInt(BASE62.length)];
        }
        return new String(out);
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
