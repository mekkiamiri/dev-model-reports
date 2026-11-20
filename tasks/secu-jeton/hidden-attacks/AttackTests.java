import com.example.token.InvalidTokenException;
import com.example.token.TokenService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Attaques cachées de la tâche secu-jeton. L'agent ne les voit pas. */
public class AttackTests {
    static final Instant T0 = Instant.parse("2026-11-19T00:00:00Z");
    static final byte[] CLE = new byte[32];

    static {
        for (int i = 0; i < 32; i++) {
            CLE[i] = (byte) (i * 7 + 3);
        }
    }

    /** Horloge réglable : on fait passer le temps sans attendre. */
    static final class Horloge extends Clock {
        Instant maintenant = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return maintenant;
        }
    }

    /** Signature calculée par l'attaquant, qui connaît la clé : seul le format doit faire refuser le jeton. */
    static String forger(String entete) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CLE, "HmacSHA256"));
        return entete + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(entete.getBytes(StandardCharsets.US_ASCII)));
    }

    static void refuse(TokenService s, String jeton) {
        Check.raises(InvalidTokenException.class, () -> s.verify(jeton));
    }

    static long exp(long secondes) {
        return T0.getEpochSecond() + secondes;
    }

    /** Source de l'agent sans commentaires ni chaînes (approximatif, suffisant pour repérer un appel). */
    static String sourceSansCommentaires() throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("src"))) {
            String tout = s.filter(p -> p.toString().endsWith(".java")).sorted().map(p -> {
                try {
                    return Files.readString(p);
                } catch (Exception e) {
                    return "";
                }
            }).collect(Collectors.joining("\n"));
            return tout.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ").replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        }
    }

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("alg-none", () -> {
            TokenService s = new TokenService(CLE, new Horloge());
            String e = String.valueOf(exp(3600));
            refuse(s, "none.alice." + e + ".");
            refuse(s, forger("none.alice." + e));
            refuse(s, forger("NONE.alice." + e));
            refuse(s, forger("hs256.alice." + e));
            refuse(s, forger("HS512.alice." + e));
            refuse(s, forger(".alice." + e));
            refuse(s, "HS256.alice." + e);
            refuse(s, "none.alice." + e + "." + forger("HS256.alice." + e).split("\\.")[3]);
        });
        a.attaque("signature-tronquee", () -> {
            TokenService s = new TokenService(CLE, new Horloge());
            String ok = s.sign("alice", Duration.ofHours(1));
            String sig = ok.substring(ok.lastIndexOf('.') + 1);
            String tete = ok.substring(0, ok.lastIndexOf('.') + 1);
            refuse(s, tete);
            refuse(s, tete + sig.substring(0, 42));
            refuse(s, tete + sig.substring(0, 32));
            refuse(s, tete + sig.substring(0, 1));
            refuse(s, tete + sig + "=");
            refuse(s, tete + sig + "A");
            refuse(s, tete + sig + sig);
            refuse(s, tete + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        });
        a.attaque("expiration", () -> {
            Horloge h = new Horloge();
            TokenService s = new TokenService(CLE, h);
            String t = s.sign("alice", Duration.ofSeconds(60));
            h.maintenant = T0.plusSeconds(59);
            Check.eq("alice", s.verify(t));
            h.maintenant = T0.plusSeconds(60);
            refuse(s, t);
            h.maintenant = T0.plusSeconds(3600);
            refuse(s, t);
            h.maintenant = T0.plusSeconds(1);
            refuse(s, forger("HS256.alice." + exp(0)));
            refuse(s, forger("HS256.alice." + exp(-10)));
        });
        a.attaque("format-exp", () -> {
            TokenService s = new TokenService(CLE, new Horloge());
            refuse(s, forger("HS256.alice.+" + exp(3600)));
            refuse(s, forger("HS256.alice.0" + exp(3600)));
            refuse(s, forger("HS256.alice.-1"));
            refuse(s, forger("HS256.alice.99999999999999999999"));
            refuse(s, forger("HS256.alice.9999999999999999999"));
            refuse(s, forger("HS256.alice." + exp(3600) + ".5"));
            refuse(s, forger("HS256.alice.١٧٩٥٠٥٠٠٠٠"));
            refuse(s, forger("HS256.alice. " + exp(3600)));
            refuse(s, forger("HS256.alice.1e12"));
        });
        a.attaque("sujet-injecte", () -> {
            TokenService s = new TokenService(CLE, new Horloge());
            for (String sujet : new String[] {"a.b", "a b", "x".repeat(65), "é", "a\nb", "admin\u0000", "a/b"}) {
                Check.raises(IllegalArgumentException.class, () -> s.sign(sujet, Duration.ofMinutes(1)));
            }
            Check.raises(IllegalArgumentException.class, () -> s.sign(null, Duration.ofMinutes(1)));
            String e = String.valueOf(exp(3600));
            refuse(s, forger("HS256.a.b." + e));
            refuse(s, forger("HS256.." + e));
            refuse(s, forger("HS256." + "x".repeat(65) + "." + e));
            refuse(s, forger("HS256.al ice." + e));
        });
        a.attaque("comparaison-constante", () -> {
            String src = sourceSansCommentaires();
            Check.isTrue(src.matches("(?s).*\\bMessageDigest\\s*\\.\\s*isEqual\\s*\\(.*")
                    || src.matches("(?s).*import\\s+static\\s+java\\.security\\.MessageDigest\\.(isEqual|\\*)\\s*;.*\\bisEqual\\s*\\(.*"),
                    "MessageDigest.isEqual absent : comparaison des signatures pas en temps constant");
            Check.isTrue(!src.matches("(?s).*\\bArrays\\s*\\.\\s*(equals|mismatch|compare)\\s*\\(.*"),
                    "Arrays.equals/mismatch/compare présent : comparaison qui s'arrête au premier écart");
        });
        a.attaque("cle-courte", () -> {
            for (int n : new int[] {0, 1, 16, 31}) {
                Check.raises(IllegalArgumentException.class, () -> new TokenService(new byte[n], new Horloge()));
            }
            Check.raises(IllegalArgumentException.class, () -> new TokenService(null, new Horloge()));
        });
        a.attaque("jeton-geant", () -> {
            TokenService s = new TokenService(CLE, new Horloge());
            String e = String.valueOf(exp(3600));
            refuse(s, "HS256." + "a".repeat(5_000_000) + "." + e + "." + "A".repeat(43));
            refuse(s, ".".repeat(1_000_000));
            refuse(s, "HS256.alice." + "9".repeat(1_000_000) + "." + "A".repeat(43));
            refuse(s, "HS256.alice." + e + "." + "A".repeat(2_000_000));
        });
        a.attaque("ttl-geant", () -> {
            Horloge h = new Horloge();
            TokenService s = new TokenService(CLE, h);
            for (Duration d : new Duration[] {Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE - 10), Duration.ofDays(1L << 40)}) {
                Attaques.borne(() -> {
                    String t = s.sign("alice", d);
                    Check.isTrue(t.split("\\.", -1)[2].matches("[1-9][0-9]{0,17}"), "exp hors format : " + t);
                    Check.eq("alice", s.verify(t));
                }, IllegalArgumentException.class);
            }
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            Horloge h = new Horloge();
            TokenService s = new TokenService(CLE, h);
            dmrref.token.TokenService ref = new dmrref.token.TokenService(CLE, h);
            String alphabet = "AZaz09_-.=+/ é";
            for (int i = 0; i < 500; i++) {
                String ok = ref.sign("u" + r.nextInt(50), Duration.ofSeconds(1 + r.nextInt(100)));
                StringBuilder t = new StringBuilder(ok);
                int n = 1 + r.nextInt(3);
                for (int j = 0; j < n && t.length() > 1; j++) {
                    int pos = r.nextInt(t.length() - 1); // jamais le dernier caractère (variantes Base64 équivalentes)
                    switch (r.nextInt(4)) {
                        case 0 -> t.setCharAt(pos, alphabet.charAt(r.nextInt(alphabet.length())));
                        case 1 -> t.deleteCharAt(pos);
                        case 2 -> t.insert(pos, alphabet.charAt(r.nextInt(alphabet.length())));
                        default -> t.setLength(pos);
                    }
                }
                String jeton = r.nextInt(8) == 0 ? ok : t.toString();
                h.maintenant = T0.plusSeconds(r.nextInt(120));
                Attaques.memeIssue(jeton, () -> s.verify(jeton), () -> ref.verify(jeton));
            }
        });
        a.fin();
    }
}
