import com.example.token.*;
import java.time.*;
import java.util.*;

public class TokenServiceTest {
    static int fails = 0;

    static void check(boolean c, String msg) {
        if (!c) { fails++; System.out.println("FAIL: " + msg); }
    }

    static void rejects(TokenService s, String t, String msg) {
        try { s.verify(t); check(false, "accepted: " + msg); }
        catch (InvalidTokenException e) { }
        catch (Throwable e) { check(false, "wrong exception " + e + " : " + msg); }
    }

    static void iae(Runnable r, String msg) {
        try { r.run(); check(false, "no IAE: " + msg); }
        catch (IllegalArgumentException e) { }
        catch (Throwable e) { check(false, "wrong exception " + e + " : " + msg); }
    }

    public static void main(String[] a) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        Clock c0 = Clock.fixed(Instant.ofEpochSecond(1000, 999_000_000), ZoneOffset.UTC);
        TokenService s = new TokenService(key, c0);

        iae(() -> new TokenService(null, c0), "null key");
        iae(() -> new TokenService(key, null), "null clock");
        iae(() -> new TokenService(new byte[31], c0), "short key");

        String t = s.sign("user_1-A", Duration.ofMillis(1500));
        check(t.startsWith("HS256.user_1-A.1001."), t);
        check(t.split("\\.")[3].length() == 43, "sig len");
        check(s.verify(t).equals("user_1-A"), "verify ok");

        iae(() -> s.sign(null, Duration.ofSeconds(5)), "null subject");
        iae(() -> s.sign("", Duration.ofSeconds(5)), "empty subject");
        iae(() -> s.sign("a".repeat(65), Duration.ofSeconds(5)), "long subject");
        iae(() -> s.sign("a.b", Duration.ofSeconds(5)), "dot subject");
        iae(() -> s.sign("é", Duration.ofSeconds(5)), "unicode subject");
        iae(() -> s.sign("a", null), "null ttl");
        iae(() -> s.sign("a", Duration.ofMillis(999)), "ttl < 1s");
        iae(() -> s.sign("a", Duration.ofSeconds(-5)), "neg ttl");
        iae(() -> s.sign("a", Duration.ofSeconds(Long.MAX_VALUE)), "huge ttl");
        s.sign("a".repeat(64), Duration.ofSeconds(1));

        // expiration
        TokenService at1001 = new TokenService(key, Clock.fixed(Instant.ofEpochSecond(1001), ZoneOffset.UTC));
        TokenService at1000 = new TokenService(key, Clock.fixed(Instant.ofEpochSecond(1000, 999_999_999), ZoneOffset.UTC));
        rejects(at1001, t, "expired at exp");
        check(at1000.verify(t).equals("user_1-A"), "valid just before exp");

        String[] p = t.split("\\.");
        rejects(s, null, "null");
        rejects(s, "", "empty");
        rejects(s, "none." + p[1] + "." + p[2] + "." + p[3], "none");
        rejects(s, "hs256." + p[1] + "." + p[2] + "." + p[3], "lowercase");
        rejects(s, "HS512." + p[1] + "." + p[2] + "." + p[3], "HS512");
        rejects(s, p[0] + "." + p[1] + "." + p[2] + ".", "empty sig");
        rejects(s, p[0] + "." + p[1] + "." + p[2], "3 parts");
        rejects(s, t + ".", "5 parts");
        rejects(s, t + "=", "padding");
        rejects(s, t.substring(0, t.length() - 1), "truncated");
        rejects(s, t + "A", "longer");
        rejects(s, p[0] + ".other." + p[2] + "." + p[3], "subject swap");
        rejects(s, p[0] + "." + p[1] + ".9999999." + p[3], "exp change");
        rejects(s, p[0] + "." + p[1] + ".01001." + p[3], "leading zero");
        rejects(s, p[0] + "." + p[1] + ".+1001." + p[3], "plus sign");
        rejects(s, p[0] + "." + p[1] + ".１００１." + p[3], "unicode digits");
        rejects(s, p[0] + "." + p[1] + ".1234567890123456789." + p[3], "19 digits");
        rejects(s, "x".repeat(100000), "huge");
        // non-canonical last base64 char
        char last = p[3].charAt(42);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int idx = alphabet.indexOf(last);
        for (int k = 1; k < 4; k++) {
            char alt = alphabet.charAt((idx & ~3) | ((idx + k) & 3));
            rejects(s, p[0] + "." + p[1] + "." + p[2] + "." + p[3].substring(0, 42) + alt, "non canonical " + alt);
        }
        // other key
        byte[] k2 = key.clone(); k2[0] = 8;
        rejects(new TokenService(k2, c0), t, "other key");
        // defensive copy
        byte[] k3 = key.clone();
        TokenService s3 = new TokenService(k3, c0);
        String t3 = s3.sign("abc", Duration.ofSeconds(10));
        k3[0] = 99;
        check(s3.verify(t3).equals("abc"), "defensive copy");
        // throwing clock
        Clock bad = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { throw new IllegalStateException("boom"); }
        };
        rejects(new TokenService(key, bad), t, "throwing clock");

        System.out.println(fails == 0 ? "ALL OK" : fails + " failures");
        if (fails != 0) System.exit(1);
    }
}
