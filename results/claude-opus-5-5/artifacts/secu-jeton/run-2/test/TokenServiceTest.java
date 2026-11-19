import com.example.token.InvalidTokenException;
import com.example.token.TokenService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TokenServiceTest {
    static int fails = 0;
    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    static void check(boolean c, String msg) {
        if (!c) { fails++; System.out.println("FAIL: " + msg); }
    }

    static void expectIAE(Runnable r, String msg) {
        try { r.run(); check(false, msg + " (no exception)"); }
        catch (IllegalArgumentException e) { }
        catch (Throwable t) { check(false, msg + " (" + t + ")"); }
    }

    static void expectInvalid(TokenService s, String tok, String msg) {
        try { s.verify(tok); check(false, msg + " accepted: " + tok); }
        catch (InvalidTokenException e) { }
        catch (Throwable t) { check(false, msg + " threw " + t); }
    }

    static String mac(byte[] key, String payload) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }

    public static void main(String[] a) throws Exception {
        Clock c = Clock.fixed(Instant.ofEpochSecond(1000, 999_000_000), ZoneOffset.UTC);
        TokenService s = new TokenService(KEY, c);

        expectIAE(() -> new TokenService(null, c), "null key");
        expectIAE(() -> new TokenService(KEY, null), "null clock");
        expectIAE(() -> new TokenService(new byte[31], c), "short key");
        expectIAE(() -> s.sign(null, Duration.ofSeconds(5)), "null subject");
        expectIAE(() -> s.sign("", Duration.ofSeconds(5)), "empty subject");
        expectIAE(() -> s.sign("a.b", Duration.ofSeconds(5)), "dot subject");
        expectIAE(() -> s.sign("x".repeat(65), Duration.ofSeconds(5)), "long subject");
        expectIAE(() -> s.sign("é", Duration.ofSeconds(5)), "non-ascii subject");
        expectIAE(() -> s.sign("bob", null), "null ttl");
        expectIAE(() -> s.sign("bob", Duration.ofMillis(999)), "ttl < 1s");
        expectIAE(() -> s.sign("bob", Duration.ofSeconds(-5)), "negative ttl");
        expectIAE(() -> s.sign("bob", Duration.ofSeconds(Long.MAX_VALUE)), "overflow ttl");

        String t = s.sign("bob_-9", Duration.ofMillis(1500));
        String expectedPayload = "HS256.bob_-9.1001";
        check(t.equals(expectedPayload + "." + mac(KEY, expectedPayload)), "format " + t);
        check(s.verify(t).equals("bob_-9"), "verify ok");
        check(s.sign("x".repeat(64), Duration.ofSeconds(1)) != null, "64 chars subject");

        // expiry
        TokenService at1001 = new TokenService(KEY, Clock.fixed(Instant.ofEpochSecond(1001), ZoneOffset.UTC));
        expectInvalid(at1001, t, "expired at exp");
        TokenService at1000 = new TokenService(KEY, Clock.fixed(Instant.ofEpochSecond(1000, 999_999_999), ZoneOffset.UTC));
        check(at1000.verify(t).equals("bob_-9"), "valid just before exp");

        // other key
        byte[] k2 = KEY.clone(); k2[0] ^= 1;
        expectInvalid(new TokenService(k2, c), t, "wrong key");

        String sig = t.substring(t.lastIndexOf('.') + 1);
        expectInvalid(s, null, "null");
        expectInvalid(s, "", "empty");
        expectInvalid(s, "...", "dots");
        expectInvalid(s, t + ".", "trailing dot");
        expectInvalid(s, t + "=", "padding");
        expectInvalid(s, t + "A", "longer sig");
        expectInvalid(s, t.substring(0, t.length() - 1), "truncated sig");
        expectInvalid(s, expectedPayload + ".", "empty sig");
        expectInvalid(s, "none.bob_-9.1001." + sig, "none");
        expectInvalid(s, "hs256.bob_-9.1001." + sig, "lowercase");
        expectInvalid(s, "HS512.bob_-9.1001." + sig, "HS512");
        expectInvalid(s, "HS256.bob_-9.01001." + mac(KEY, "HS256.bob_-9.01001"), "leading zero");
        expectInvalid(s, "HS256.bob_-9.+1001." + mac(KEY, "HS256.bob_-9.+1001"), "plus sign");
        expectInvalid(s, "HS256.bob.1234567890123456789." + mac(KEY, "HS256.bob.1234567890123456789"), "19 digits");
        expectInvalid(s, "HS256.bo b.1001." + mac(KEY, "HS256.bo b.1001"), "space subject");
        expectInvalid(s, "HS256.alice.1001." + sig, "subject swap");
        expectInvalid(s, "HS256.bob_-9.99999." + sig, "exp change");
        expectInvalid(s, "HS256.bob_-9.١٠٠١." + sig, "arabic digits");
        expectInvalid(s, "x".repeat(100000), "huge");
        // non-canonical last char: change low unused bits
        char last = sig.charAt(42);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int idx = alphabet.indexOf(last);
        String alt = expectedPayload + "." + sig.substring(0, 42) + alphabet.charAt(idx ^ 1);
        expectInvalid(s, alt, "non-canonical base64");
        String std = sig.replace('-', '+').replace('_', '/');
        if (!std.equals(sig)) expectInvalid(s, expectedPayload + "." + std, "standard base64 alphabet");

        // valid max-digit exp
        TokenService big = new TokenService(KEY, Clock.fixed(Instant.ofEpochSecond(0), ZoneOffset.UTC));
        String p = "HS256.z.999999999999999999";
        check(big.verify(p + "." + mac(KEY, p)).equals("z"), "18 digits ok");

        // throwing clock
        Clock bad = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { throw new IllegalStateException("boom"); }
        };
        expectInvalid(new TokenService(KEY, bad), t, "throwing clock");

        // key mutation after construction
        byte[] km = KEY.clone();
        TokenService sm = new TokenService(km, c);
        String tm = sm.sign("bob", Duration.ofSeconds(10));
        km[0] ^= 1;
        check(sm.verify(tm).equals("bob"), "defensive key copy");

        System.out.println(fails == 0 ? "ALL OK" : fails + " failure(s)");
        System.exit(fails == 0 ? 0 : 1);
    }
}
