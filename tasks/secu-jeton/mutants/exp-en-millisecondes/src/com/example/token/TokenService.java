package com.example.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TokenService {
    private static final String ALG = "HS256";
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EXP = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Clock clock;

    public TokenService(byte[] key, Clock clock) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("clé HMAC de 32 octets au moins");
        }
        if (clock == null) {
            throw new IllegalArgumentException("horloge absente");
        }
        this.key = key.clone();
        this.clock = clock;
    }

    public String sign(String subject, Duration ttl) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new IllegalArgumentException("sujet invalide");
        }
        if (ttl == null || ttl.getSeconds() < 1) {
            throw new IllegalArgumentException("durée de validité d'une seconde au moins");
        }
        String exp;
        try {
            exp = Long.toString(Math.addExact(clock.millis(), ttl.toMillis()));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("expiration trop lointaine");
        }
        if (!EXP.matcher(exp).matches()) {
            throw new IllegalArgumentException("expiration trop lointaine");
        }
        String payload = ALG + "." + subject + "." + exp;
        return payload + "." + ENCODER.encodeToString(mac(payload));
    }

    public String verify(String token) {
        if (token == null) {
            throw new InvalidTokenException("jeton absent");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !ALG.equals(parts[0]) || !SUBJECT.matcher(parts[1]).matches()
                || !EXP.matcher(parts[2]).matches() || !SIGNATURE.matcher(parts[3]).matches()) {
            throw new InvalidTokenException("jeton mal formé");
        }
        byte[] given = Base64.getUrlDecoder().decode(parts[3]);
        byte[] expected = mac(parts[0] + "." + parts[1] + "." + parts[2]);
        if (!MessageDigest.isEqual(expected, given)) {
            throw new InvalidTokenException("signature invalide");
        }
        if (now() >= Long.parseLong(parts[2])) {
            throw new InvalidTokenException("jeton expiré");
        }
        return parts[1];
    }

    private long now() {
        return clock.instant().getEpochSecond();
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }
}
