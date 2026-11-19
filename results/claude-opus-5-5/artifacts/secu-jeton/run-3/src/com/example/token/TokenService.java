package com.example.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Délivre et vérifie des jetons {@code HS256.<sujet>.<exp>.<signature>} signés en HMAC-SHA256.
 * Le jeton passé à {@link #verify(String)} est traité comme une donnée hostile.
 */
public final class TokenService {

    private static final String ALG = "HS256";
    private static final String MAC_ALG = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int MAX_SUBJECT = 64;
    private static final int MAX_EXP_DIGITS = 18;
    private static final int SIG_CHARS = 43;
    /** Longueur maximale d'un jeton bien formé. */
    private static final int MAX_TOKEN_LENGTH =
            ALG.length() + 1 + MAX_SUBJECT + 1 + MAX_EXP_DIGITS + 1 + SIG_CHARS;
    /** Plus grande valeur de exp représentable sur 18 chiffres. */
    private static final long MAX_EXP = 999_999_999_999_999_999L;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Clock clock;

    public TokenService(byte[] key, Clock clock) {
        if (key == null) {
            throw new IllegalArgumentException("key is null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock is null");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = key.clone(); // copie défensive
        this.clock = clock;
        newMac(); // échoue tôt si HmacSHA256 est indisponible
    }

    public String sign(String subject, Duration ttl) {
        if (!isValidSubject(subject)) {
            throw new IllegalArgumentException("invalid subject");
        }
        if (ttl == null) {
            throw new IllegalArgumentException("ttl is null");
        }
        long ttlSeconds = ttl.getSeconds(); // troncature à la seconde
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("ttl must be at least one second");
        }
        long now = clock.instant().getEpochSecond();
        long exp;
        try {
            exp = Math.addExact(now, ttlSeconds);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ttl too large");
        }
        if (exp < 0 || exp > MAX_EXP) {
            throw new IllegalArgumentException("expiration out of range");
        }
        String payload = ALG + "." + subject + "." + exp;
        return payload + "." + computeSignature(payload);
    }

    public String verify(String token) {
        try {
            return doVerify(token);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // Aucune autre exception ne doit fuiter vers l'appelant.
            throw new InvalidTokenException("invalid token");
        }
    }

    private String doVerify(String token) {
        if (token == null) {
            throw new InvalidTokenException("token is null");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidTokenException("malformed token");
        }
        // Découpage manuel : exactement quatre parties, parties vides conservées.
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        int d3 = d2 < 0 ? -1 : token.indexOf('.', d2 + 1);
        if (d1 < 0 || d2 < 0 || d3 < 0 || token.indexOf('.', d3 + 1) >= 0) {
            throw new InvalidTokenException("malformed token");
        }
        String alg = token.substring(0, d1);
        String subject = token.substring(d1 + 1, d2);
        String expText = token.substring(d2 + 1, d3);
        String signature = token.substring(d3 + 1);

        if (!ALG.equals(alg)) {
            throw new InvalidTokenException("unsupported algorithm");
        }
        if (!isValidSubject(subject)) {
            throw new InvalidTokenException("invalid subject");
        }
        if (!isValidExp(expText)) {
            throw new InvalidTokenException("invalid expiration");
        }
        if (!isValidSignatureText(signature)) {
            throw new InvalidTokenException("invalid signature");
        }

        String payload = token.substring(0, d3);
        byte[] expected = computeSignature(payload).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        // Comparaison en temps constant de l'encodage canonique : rejette aussi les
        // encodages Base64 non canoniques (bits de remplissage finaux non nuls).
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidTokenException("invalid signature");
        }

        long exp = Long.parseLong(expText); // sûr : 1 à 18 chiffres ASCII
        long now = clock.instant().getEpochSecond();
        if (now >= exp) {
            throw new InvalidTokenException("token expired");
        }
        return subject;
    }

    private String computeSignature(String payload) {
        byte[] raw = newMac().doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        return B64.encodeToString(raw);
    }

    /** Mac n'est pas thread-safe : une instance par appel. */
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(MAC_ALG);
            mac.init(new SecretKeySpec(key, MAC_ALG));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isBase64UrlChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isValidSubject(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_SUBJECT) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isBase64UrlChar(s.charAt(i))) { // même alphabet que le sujet
                return false;
            }
        }
        return true;
    }

    private static boolean isValidExp(String s) {
        if (s.isEmpty() || s.length() > MAX_EXP_DIGITS) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        // Pas de zéro en tête ; "0" seul est accepté syntaxiquement (et toujours expiré).
        return !(s.length() > 1 && s.charAt(0) == '0');
    }

    private static boolean isValidSignatureText(String s) {
        if (s.length() != SIG_CHARS) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isBase64UrlChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
