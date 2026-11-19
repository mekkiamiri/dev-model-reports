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
 * Jetons signés HMAC-SHA256 au format {@code HS256.<sujet>.<exp>.<signature>}.
 * Thread-safe : une instance de {@link Mac} est créée à chaque calcul.
 */
public final class TokenService {

    private static final String ALG = "HS256";
    private static final String MAC_ALG = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int MAX_SUBJECT = 64;
    private static final int MAX_EXP_DIGITS = 18;
    private static final int SIG_LEN = 43;
    /** Longueur maximale d'un jeton valide : 5 + 1 + 64 + 1 + 18 + 1 + 43. */
    private static final int MAX_TOKEN_LEN =
            ALG.length() + 1 + MAX_SUBJECT + 1 + MAX_EXP_DIGITS + 1 + SIG_LEN;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;
    private final Clock clock;

    public TokenService(byte[] key, Clock clock) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        // SecretKeySpec copie le tableau : une modification ultérieure par l'appelant est sans effet.
        this.key = new SecretKeySpec(key, MAC_ALG);
        this.clock = clock;
        // Vérifie dès la construction que HmacSHA256 est disponible.
        newMac();
    }

    public String sign(String subject, Duration ttl) {
        if (!isValidSubject(subject)) {
            throw new IllegalArgumentException("invalid subject");
        }
        if (ttl == null) {
            throw new IllegalArgumentException("ttl must not be null");
        }
        long ttlSeconds = ttl.getSeconds(); // tronqué à la seconde pour une durée positive
        if (ttl.isNegative() || ttlSeconds < 1) {
            throw new IllegalArgumentException("ttl must be at least one second");
        }
        long now = clock.instant().getEpochSecond();
        long exp;
        try {
            exp = Math.addExact(now, ttlSeconds);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("expiration overflows", e);
        }
        String expText = Long.toString(exp);
        if (exp < 0 || !isValidExp(expText)) {
            throw new IllegalArgumentException("expiration not representable");
        }
        String payload = ALG + "." + subject + "." + expText;
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    public String verify(String token) {
        try {
            return doVerify(token);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // Jamais d'autre exception que InvalidTokenException vers l'appelant.
            throw new InvalidTokenException("invalid token");
        }
    }

    private String doVerify(String token) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LEN) {
            throw new InvalidTokenException("invalid token");
        }
        // Découpage manuel : exactement trois points, donc quatre parties.
        int d1 = token.indexOf('.');
        int d2 = d1 < 0 ? -1 : token.indexOf('.', d1 + 1);
        int d3 = d2 < 0 ? -1 : token.indexOf('.', d2 + 1);
        if (d1 < 0 || d2 < 0 || d3 < 0 || token.indexOf('.', d3 + 1) >= 0) {
            throw new InvalidTokenException("malformed token");
        }
        String alg = token.substring(0, d1);
        String subject = token.substring(d1 + 1, d2);
        String expText = token.substring(d2 + 1, d3);
        String sig = token.substring(d3 + 1);

        if (!ALG.equals(alg)) {
            throw new InvalidTokenException("unsupported algorithm");
        }
        if (!isValidSubject(subject) || !isValidExp(expText)) {
            throw new InvalidTokenException("malformed token");
        }
        if (sig.length() != SIG_LEN || !isBase64UrlAlphabet(sig)) {
            throw new InvalidTokenException("malformed signature");
        }

        // Comparaison en temps constant (MessageDigest.isEqual) de la forme canonique encodée :
        // cela refuse aussi les variantes non canoniques (bits de remplissage non nuls dans
        // le dernier caractère), que le décodeur Base64 du JDK accepterait.
        String payload = token.substring(0, d3);
        byte[] expected = B64.encodeToString(hmac(payload)).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sig.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidTokenException("bad signature");
        }

        long exp = Long.parseLong(expText); // 18 chiffres au plus : pas de dépassement
        long now = clock.instant().getEpochSecond();
        if (now >= exp) {
            throw new InvalidTokenException("token expired");
        }
        return subject;
    }

    private byte[] hmac(String payload) {
        return newMac().doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(MAC_ALG);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isSafeChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static boolean isValidSubject(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_SUBJECT) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isSafeChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidExp(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_EXP_DIGITS) {
            return false;
        }
        if (s.length() > 1 && s.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** L'alphabet Base64 URL coïncide avec celui du sujet : A-Z, a-z, 0-9, '-', '_'. */
    private static boolean isBase64UrlAlphabet(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isSafeChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
