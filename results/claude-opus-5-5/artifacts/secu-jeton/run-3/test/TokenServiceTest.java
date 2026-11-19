import com.example.token.InvalidTokenException;
import com.example.token.TokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

public class TokenServiceTest {
    static int failures = 0;

    static void check(boolean cond, String name) {
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    static void rejects(TokenService s, String token, String name) {
        try {
            s.verify(token);
            check(false, name + " (accepted)");
        } catch (InvalidTokenException e) {
            // ok
        } catch (Throwable t) {
            check(false, name + " (wrong exception " + t + ")");
        }
    }

    static void iae(Runnable r, String name) {
        try {
            r.run();
            check(false, name + " (no exception)");
        } catch (IllegalArgumentException e) {
            // ok
        } catch (Throwable t) {
            check(false, name + " (wrong exception " + t + ")");
        }
    }

    public static void main(String[] args) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_000_000, 999_000_000), ZoneOffset.UTC);
        TokenService s = new TokenService(key, clock);

        iae(() -> new TokenService(null, clock), "null key");
        iae(() -> new TokenService(key, null), "null clock");
        iae(() -> new TokenService(new byte[31], clock), "short key");
        iae(() -> s.sign(null, Duration.ofSeconds(5)), "null subject");
        iae(() -> s.sign("", Duration.ofSeconds(5)), "empty subject");
        iae(() -> s.sign("a".repeat(65), Duration.ofSeconds(5)), "long subject");
        iae(() -> s.sign("a.b", Duration.ofSeconds(5)), "dot subject");
        iae(() -> s.sign("é", Duration.ofSeconds(5)), "unicode subject");
        iae(() -> s.sign("bob", null), "null ttl");
        iae(() -> s.sign("bob", Duration.ofMillis(999)), "ttl < 1s");
        iae(() -> s.sign("bob", Duration.ofSeconds(-5)), "negative ttl");
        iae(() -> s.sign("bob", Duration.ofSeconds(Long.MAX_VALUE)), "huge ttl");

        String t = s.sign("bob_-9", Duration.ofMillis(1500));
        check(t.startsWith("HS256.bob_-9.1000001."), "format " + t);
        check(t.split("\\.")[3].length() == 43, "sig length");
        check("bob_-9".equals(s.verify(t)), "roundtrip");
        String sub64 = "Z".repeat(64);
        check(sub64.equals(s.verify(s.sign(sub64, Duration.ofSeconds(1)))), "64 chars subject");

        // key copy
        byte[] k2 = key.clone();
        TokenService s2 = new TokenService(k2, clock);
        String t2 = s2.sign("x", Duration.ofSeconds(10));
        Arrays.fill(k2, (byte) 0);
        check("x".equals(s2.verify(t2)), "defensive key copy");

        // expiry boundary
        Clock atExp = Clock.fixed(Instant.ofEpochSecond(1_000_001), ZoneOffset.UTC);
        Clock before = Clock.fixed(Instant.ofEpochSecond(1_000_000, 999_999_999), ZoneOffset.UTC);
        rejects(new TokenService(key, atExp), t, "expired at exp");
        check("bob_-9".equals(new TokenService(key, before).verify(t)), "valid just before exp");

        String[] p = t.split("\\.");
        String sig = p[3];
        rejects(s, null, "null");
        rejects(s, "", "empty");
        rejects(s, "....", "dots");
        rejects(s, t + ".", "trailing dot");
        rejects(s, t + ".x", "five parts");
        rejects(s, "HS256.bob_-9.1000001", "three parts");
        rejects(s, "none." + p[1] + "." + p[2] + "." + sig, "none");
        rejects(s, "none." + p[1] + "." + p[2] + ".", "none empty sig");
        rejects(s, "hs256." + p[1] + "." + p[2] + "." + sig, "lowercase alg");
        rejects(s, "HS512." + p[1] + "." + p[2] + "." + sig, "HS512");
        rejects(s, p[0] + "." + p[1] + "." + p[2] + ".", "empty sig");
        rejects(s, p[0] + "." + p[1] + "." + p[2] + "." + sig.substring(0, 42), "truncated sig");
        rejects(s, t + "A", "longer sig");
        rejects(s, t + "=", "padded sig");
        rejects(s, p[0] + "." + p[1] + "." + p[2] + "." + sig.substring(0, 42) + "+", "std b64 char");
        rejects(s, p[0] + ".alice." + p[2] + "." + sig, "subject swap");
        rejects(s, p[0] + "." + p[1] + ".9999999." + sig, "exp change");
        rejects(s, p[0] + "." + p[1] + ".01000001." + sig, "leading zero");
        rejects(s, p[0] + "." + p[1] + ".+1000001." + sig, "signed exp");
        rejects(s, p[0] + "." + p[1] + ".1234567890123456789." + sig, "19 digits");
        rejects(s, p[0] + "." + p[1] + ".١٢٣." + sig, "arabic digits");
        rejects(s, p[0] + ".." + p[2] + "." + sig, "empty subject");
        rejects(s, p[0] + ".bób." + p[2] + "." + sig, "unicode subject");
        rejects(s, " " + t, "leading space");
        rejects(s, t + "\n", "trailing newline");
        rejects(s, "x".repeat(1_000_000), "huge");

        // Non-canonical base64: alter the 2 unused low bits of last char.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int idx = alphabet.indexOf(sig.charAt(42));
        for (int d = 1; d < 4; d++) {
            char alt = alphabet.charAt((idx & ~3) | ((idx + d) & 3));
            rejects(s, p[0] + "." + p[1] + "." + p[2] + "." + sig.substring(0, 42) + alt, "non-canonical " + d);
        }
        // other key
        byte[] other = key.clone();
        other[0] = 8;
        rejects(new TokenService(other, clock), t, "other key");

        System.out.println(failures == 0 ? "ALL OK" : failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
