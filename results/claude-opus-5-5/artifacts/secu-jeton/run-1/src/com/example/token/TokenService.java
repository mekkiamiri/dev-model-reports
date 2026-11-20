package com.example.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Émission et vérification de jetons {@code HS256.<sujet>.<exp>.<signature>} signés en HMAC-SHA256. */
public final class TokenService {
    private static final String ALG = "HS256";
    private static final String MAC_ALG = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int MAX_SUBJECT = 64;
    private static final int MAX_EXP_DIGITS = 18;
    private static final long MAX_EXP = 999_999_999_999_999_999L;
    private static final int SIG_LEN = 43;
    /** Longueur maximale d'un jeton bien formé : rejet précoce des entrées démesurées. */
    private static final int MAX_TOKEN_LEN =
            ALG.length() + 1 + MAX_SUBJECT + 1 + MAX_EXP_DIGITS + 1 + SIG_LEN;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Clock clock;

    public TokenService(byte[] key, Clock clock) {
        if (key == null) throw new IllegalArgumentException("key is null");
        if (clock == null) throw new IllegalArgumentException("clock is null");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("key must be at least 32 bytes");
        }
        this.key = key.clone(); // copie défensive : l'appelant ne peut plus modifier la clé
        this.clock = clock;
    }

    public String sign(String subject, Duration ttl) {
        if (!isValidSubject(subject)) throw new IllegalArgumentException("invalid subject");
        if (ttl == null) throw new IllegalArgumentException("ttl is null");
        long ttlSeconds = ttl.getSeconds(); // tronqué à la seconde (nanos ignorés)
        if (ttlSeconds < 1) throw new IllegalArgumentException("ttl must be at least one second");
        long now = clock.instant().getEpochSecond();
        long exp;
        try {
            exp = Math.addExact(now, ttlSeconds);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ttl too large");
        }
        // exp doit rester représentable dans le format (pas de signe, 18 chiffres au plus).
        if (exp < 0 || exp > MAX_EXP) throw new IllegalArgumentException("ttl too large");
        String payload = ALG + "." + subject + "." + exp;
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    public String verify(String token) {
        try {
            return doVerify(token);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // Aucune autre exception ne doit s'échapper de verify.
            throw new InvalidTokenException("invalid token");
        }
    }

    private String doVerify(String token) {
        if (token == null) throw new InvalidTokenException("token is null");
        if (token.length() > MAX_TOKEN_LEN) throw new InvalidTokenException("malformed token");
        String[] parts = token.split("\\.", -1); // -1 : conserve les parties vides
        if (parts.length != 4) throw new InvalidTokenException("malformed token");
        String alg = parts[0];
        String subject = parts[1];
        String expStr = parts[2];
        String sig = parts[3];

        if (!ALG.equals(alg)) throw new InvalidTokenException("unsupported algorithm");
        if (!isValidSubject(subject)) throw new InvalidTokenException("malformed subject");
        if (!isValidExp(expStr)) throw new InvalidTokenException("malformed expiration");
        if (!isBase64UrlOfLength(sig, SIG_LEN)) throw new InvalidTokenException("malformed signature");

        String payload = ALG + "." + subject + "." + expStr;
        byte[] expected = B64.encodeToString(hmac(payload)).getBytes(StandardCharsets.US_ASCII);
        byte[] given = sig.getBytes(StandardCharsets.US_ASCII);
        // Comparaison en temps constant de la forme encodée canonique : rejette aussi les
        // variantes Base64 non canoniques (bits de remplissage non nuls dans le dernier caractère).
        if (!MessageDigest.isEqual(expected, given)) throw new InvalidTokenException("bad signature");

        long exp = Long.parseLong(expStr); // sûr : 18 chiffres ASCII au plus
        long now = clock.instant().getEpochSecond();
        if (now >= exp) throw new InvalidTokenException("token expired");
        return subject;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALG); // instance par appel : thread-safe
            mac.init(new SecretKeySpec(key, MAC_ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isUrlSafeChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
    }

    private static boolean isValidSubject(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_SUBJECT) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isUrlSafeChar(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isValidExp(String s) {
        if (s.isEmpty() || s.length() > MAX_EXP_DIGITS) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false; // pas de Character.isDigit (chiffres Unicode)
        }
        return s.length() == 1 || s.charAt(0) != '0';
    }

    private static boolean isBase64UrlOfLength(String s, int len) {
        if (s.length() != len) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isUrlSafeChar(s.charAt(i))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "TokenService[key=***]";
    }
}
